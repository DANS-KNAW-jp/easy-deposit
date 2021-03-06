/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.api.sword2

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.Collections

import gov.loc.repository.bagit.BagFactory
import gov.loc.repository.bagit.utilities.SimpleResult
import net.lingala.zip4j.core.ZipFile
import org.apache.abdera.i18n.iri.IRI
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.slf4j.LoggerFactory
import org.swordapp.server.{Deposit, DepositReceipt, SwordError}
import rx.lang.scala.schedulers.NewThreadScheduler
import rx.lang.scala.subjects.PublishSubject

import scala.util.{Failure, Success, Try}

object DepositHandler {
  val log = LoggerFactory.getLogger(getClass)

  implicit val bagFactory = new BagFactory

  val depositProcessingStream = PublishSubject[(String, Deposit)]()

  depositProcessingStream
    .onBackpressureBuffer
    .observeOn(NewThreadScheduler())
    .doOnEach(_ match { case (id, deposit) => finalizeDeposit(deposit.getMimeType)(id) })
    .subscribe(_ match { case (id, deposit) => log.info(s"Done finalizing deposit $id") })

  def handleDeposit(deposit: Deposit)(implicit id: String): Try[DepositReceipt] = {
    val payload = Paths.get(SwordProps("tempdir"), id, deposit.getFilename.split("/").last).toFile
    for {
      _ <- copyPayloadToFile(deposit, payload)
      _ <- doesHashMatch(payload, deposit.getMd5)
      _ <- handleDepositAsync(deposit)
    } yield createDepositReceipt(deposit, id)
  }

  def finalizeDeposit(mimeType: String)(implicit id: String): Try[Unit] = {
    log.info(s"[$id] Finalizing deposit")
    val tempDir = new File(SwordProps("tempdir"), id)
    (for {
      git      <- initGit(tempDir)
      _        <- extractBag(mimeType)
      bagitDir <- getBagDir(tempDir)
      _        <- checkBagValidity(bagitDir)
      _        <- DepositProperties.set(id, "SUBMITTED", "Deposit is valid and ready for post-submission processing", lookInTempFirst = true)
      _        <- commitSubmitted(git, tempDir)
      dataDir  <- moveBagToStorage()
    } yield ())
    .recover {
      case InvalidDepositException(_, msg, cause) =>
        log.warn(s"[$id] Invalid deposit", cause)
        DepositProperties.set(id, "INVALID", msg, lookInTempFirst = true)
      case FailedDepositException(_, msg, cause) =>
        log.error(s"[$id] Failed deposit", cause)
        DepositProperties.set(id, "FAILED", msg, lookInTempFirst = true)
      case cause: Throwable =>
        log.error(s"[$id] Unexpected failure in deposit", cause)
        DepositProperties.set(id, "FAILED", "Unexpected failure in deposit", lookInTempFirst = true)
    }
  }

  private def initGit(bagDir: File)(implicit id: String): Try[Option[Git]] =
    // TODO: refactor this, so that errors thrown by toBoolean are actually caught somewhere!
    if (SwordProps("git.enabled").toBoolean)
      Try {
        log.debug("Initializing git repo for deposit")
        Some(Git.init().setDirectory(bagDir).call())
      }.recoverWith { case e => Failure(new FailedDepositException(id, "Failed to initialize versioning", e)) }
    else
      Success(None)

  private def extractBag(mimeType: String)(implicit id: String): Try[File] = {
    def extract(file: File, outputPath: String): Unit = new ZipFile(file.getPath).extractAll(outputPath)

    def getSequenceNumber(f: File): Int =
      try {
        f.getName.split('.').last.toInt
      } catch {
        case _: Throwable =>
          throw new InvalidDepositException(id, s"Partial file ${f.getName} has an incorrect extension. Should be a sequence number.")
      }

    Try {
      log.debug(s"[$id] Extracting bag")
      val tempDir: File = new File(SwordProps("tempdir"), id)
      val files = tempDir.listFilesSafe.filter(isPartOfDeposit)
      mimeType match {
        case "application/zip" =>
          files.foreach(file => {
            if (!file.isFile)
              throw new FailedDepositException(id, s"Inconsistent dataset: non-file object found: ${file.getName}")
            extract(file, tempDir.getPath)
            deleteQuietly(file)
          })
        case "application/octet-stream" =>
          val mergedZip = new File(tempDir, "merged.zip")
          files.foreach(f => log.debug(s"[$id] Merging file: ${f.getName}"))
          MergeFiles.merge(mergedZip, files.sortBy(getSequenceNumber))
          extract(mergedZip, tempDir.getPath)
          files.foreach(deleteQuietly)
          deleteQuietly(mergedZip)
        case _ =>
          throw new InvalidDepositException(id, s"Invalid content type: $mimeType")
      }
      tempDir
    }
  }

  private def getBagDir(depositDir: File): Try[File] = Try {
    depositDir.listFiles.find(f => f.isDirectory && isPartOfDeposit(f)).get
  }

  def checkDepositIsInDraft(id: String): Try[Unit] =
    (for {
      state <- DepositProperties.getState(id)
      if state.label == "DRAFT"
    } yield ())
      .recoverWith {
        case t => Failure(new SwordError("http://purl.org/net/sword/error/MethodNotAllowed", 405, s"Deposit $id is not in DRAFT state."))
      }

  def copyPayloadToFile(deposit: Deposit, zipFile: File)(implicit id: String): Try[Unit] =
    try {
      log.debug(s"[$id] Copying payload to: $zipFile")
      Success(copyInputStreamToFile(deposit.getInputStream, zipFile))
    } catch {
      case t: Throwable => Failure(new SwordError("http://purl.org/net/sword/error/ErrorBadRequest", t))
    }

  def handleDepositAsync(deposit: Deposit)(implicit id: String): Try[Unit] = Try {
    if (!deposit.isInProgress) {
      log.info(s"[$id] Scheduling deposit to be finalized")
      DepositProperties.set(id, "FINALIZING", "Deposit is being reassembled and validated", lookInTempFirst = true)
      depositProcessingStream.onNext((id, deposit))
    } else {
      log.info(s"[$id] Received continuing deposit: ${deposit.getFilename}")
    }
  }

  private def checkBagValidity(bagitDir: File)(implicit id: String): Try[Unit] = {
    log.debug(s"[$id] Verifying bag validity")
    val bag = bagFactory.createBag(bagitDir, BagFactory.Version.V0_97, BagFactory.LoadOption.BY_MANIFESTS)
    val validationResult: SimpleResult = bag.verifyValid
    if (validationResult.isSuccess) Success(Unit)
    else Failure(new InvalidDepositException(id, validationResult.messagesToString))
  }

  private def commitSubmitted(optionalGit: Option[Git], bagDir: File)(implicit id: String): Try[Option[Ref]] =
    Try {
      optionalGit.map(git => {
        git.add().addFilepattern(".").call()
        git.commit().setCommitter(SwordProps("git.user"), SwordProps("git.email")).setMessage("initial commit").call()
        git.tag().setName("state=SUBMITTED").setMessage("state=SUBMITTED").call()
      })
    }.recoverWith { case e => Failure(new FailedDepositException(id, "Failed to set bag status to SUBMITTED", e)) }

  def moveBagToStorage()(implicit id: String): Try[File] =
    Try {
      log.debug("Moving bag to permanent storage")
      val tempDir = new File(SwordProps("tempdir"), id)
      val storageDir = new File(SwordProps("deposits-root"), id)
      if(!tempDir.renameTo(storageDir)) throw new SwordError(s"Cannot move $tempDir to $storageDir")
      storageDir
    }.recover { case e => throw new SwordError("Failed to move dataset to storage", e) }

  def doesHashMatch(zipFile: File, MD5: String): Try[Unit] = {
    log.debug(s"Checking Content-MD5 (Received: $MD5)")
    lazy val fail = Failure(new SwordError("http://purl.org/net/sword/error/ErrorChecksumMismatch"))
    val is = Files.newInputStream(Paths.get(zipFile.getPath))
    try {
      if (MD5 == DigestUtils.md5Hex(is)) Success(Unit)
      else fail
    } catch {
      case _: Throwable => fail
    } finally {
      Try { is.close() }
    }
  }

  def createDepositReceipt(deposit: Deposit, id: String): DepositReceipt = {
    val dr = new DepositReceipt
    val editIRI = new IRI(SwordProps("base-url") + "/container/" + id)
    val editMediaIri = new IRI(SwordProps("base-url") + "/media/" + id)
    val stateIri = SwordProps("base-url") + "/statement/" + id
    dr.setEditIRI(editIRI)
    dr.setLocation(editIRI)
    dr.setEditMediaIRI(editMediaIri)
    dr.setSwordEditIRI(editMediaIri)
    dr.setAtomStatementURI(stateIri)
    dr.setPackaging(Collections.singletonList("http://purl.org/net/sword/package/BagIt"))
    dr.setTreatment("[1] unpacking [2] verifying integrity [3] storing persistently")
    dr.setVerboseDescription("received successfully: " + deposit.getFilename + "; MD5: " + deposit.getMd5)
    dr
  }
}
