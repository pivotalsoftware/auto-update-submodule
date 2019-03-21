/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.sendgrid.SendGrid
import org.slf4j.LoggerFactory

@Grab('com.sendgrid:sendgrid-java:0.1.2')
@Grab('spring-boot-starter-actuator')
@Controller
class AutoUpdateSubmodule {

  def REPOSITORY_DIRECTORY = new File(System.getProperty('java.io.tmpdir'), 'repo')

  def logger = LoggerFactory.getLogger(this.getClass())

  @Value('${uri}')
  def uri

  @Value('${from.address}')
  def fromAddress

  @Value('${to.address}')
  def toAddress

  @Value('${vcap.services.sendgrid.credentials.username}')
  def username

  @Value('${vcap.services.sendgrid.credentials.password}')
  def password

  @RequestMapping(method = RequestMethod.POST, value = '/')
  ResponseEntity<Void> webhook() {
    Thread.start { update() }
    return new ResponseEntity<>(HttpStatus.OK)
  }

  def update() {
    if (!REPOSITORY_DIRECTORY.exists()) {
      cloneRepository()
    }

    updateRemote()
    def updateSuccessful = attemptUpdateSubmodule()

    if (updateSuccessful) {
      pushRepository()
    } else {
      sendFailureEmail()
    }

    logger.info('Auto update submodule complete')
  }

  def cloneRepository() {
    logger.info('Creating repository')

    REPOSITORY_DIRECTORY.mkdirs()

    inRepository(['git', 'init'])
    inRepository(['git', 'config', 'user.email', fromAddress])
    inRepository(['git', 'config', 'user.name', 'Auto Submodule Update'])
    inRepository(['git', 'remote', 'add', 'origin', uri])
  }

  def updateRemote() {
    logger.info('Updating origin/master')
    inRepository(['git', 'fetch', '-u', 'origin'])
    inRepository(['git', 'reset', '--hard', 'origin/master'])
    inRepository(['git', 'submodule', 'update', '--init', '--recursive'])
  }

  def attemptUpdateSubmodule() {
    logger.info('Attempting submodule update')
    def updateSuccess = inRepository(['git', 'submodule', 'foreach', 'git', 'pull', 'origin', 'master']).exitValue() == 0
    def commitSuccess = inRepository(['git', 'commit', '-a', '-m', 'Submodule updated']).exitValue() == 0
    updateSuccess && commitSuccess
  }

  def pushRepository() {
    logger.info('Pushing origin/master')
    inRepository(['git', 'push', 'origin', 'master'])
  }

  def sendFailureEmail() {
    logger.info("Sending failure email to ${toAddress}")

    def sendGrid = new SendGrid(username, password)
    sendGrid.setFrom(fromAddress)
    sendGrid.addTo(toAddress)
    sendGrid.setSubject('Unable to update submodules')
    sendGrid.setText("An attempt to update submodules in ${sterilizeUri(uri)} has failed.  This update must be " +
                     "executed manually.")
    sendGrid.send()
  }

  def sterilizeUri(s) {
    def uri = new URI(s)
    "${uri.scheme}://${uri.host}${uri.path}"
  }

  def inRepository(command) {
    def proc = command.execute(null, REPOSITORY_DIRECTORY)
    proc.waitFor()

    if (proc.exitValue() != 0) {
      logger.error("stdout: {}", proc.in.text)
      logger.error("stderr: {}", proc.err.text)
    }

    proc
  }
}
