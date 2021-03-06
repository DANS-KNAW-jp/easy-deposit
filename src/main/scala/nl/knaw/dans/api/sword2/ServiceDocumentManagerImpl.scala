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

import org.swordapp.server._
import org.slf4j.LoggerFactory

class ServiceDocumentManagerImpl extends ServiceDocumentManager {
  val log = LoggerFactory.getLogger(getClass)
  val BAGIT_URI = "http://purl.org/net/sword/package/BagIt"

  @throws(classOf[SwordError])
  @throws(classOf[SwordServerException])
  @throws(classOf[SwordAuthException])
  def getServiceDocument(s: String, authCredentials: AuthCredentials, swordConfiguration: SwordConfiguration): ServiceDocument = {
    log.info("Service document retrieved by {}",
      if (authCredentials.getUsername.isEmpty) "Anonymous user"
      else authCredentials.getUsername)
    val sdoc: ServiceDocument = new ServiceDocument
    val sw: SwordWorkspace = new SwordWorkspace
    sw.setTitle("EASY Deposit Service")
    val sc: SwordCollection = new SwordCollection
    sc.setTitle("DANS Default Data Collection")
    sc.addAcceptPackaging(BAGIT_URI)
    sc.setLocation(SwordProps("collection.iri"))
    sw.addCollection(sc)
    sdoc.addWorkspace(sw)
    sdoc
  }
}
