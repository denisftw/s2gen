package com.appliedscala.generator.services

import com.appliedscala.generator.errors.ShutdownHookRegistrationError
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import zio.blocking._
import zio.ZIO
import zio.Task

class ShutdownService(httpServerService: HttpServerService) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerHook(maybeServer: Option[Server]): ZIO[Blocking, ShutdownHookRegistrationError, Unit] = {
    blocking {
      Task {
        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            logger.info("Stopping the system")
            httpServerService.stop(maybeServer)
          }
        })
      }.refineOrDie { case th: Throwable =>
        ShutdownHookRegistrationError(th)
      }
    }
  }
}
