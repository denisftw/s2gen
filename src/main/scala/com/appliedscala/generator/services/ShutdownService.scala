package com.appliedscala.generator.services

import better.files.FileMonitor
import com.appliedscala.generator.errors.ShutdownHookRegistrationError
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import zio.IO

class ShutdownService(httpServerService: HttpServerService) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerHook(maybeServer: Option[Server], fileMonitor: FileMonitor): IO[ShutdownHookRegistrationError, Unit] = {
    IO.effectSuspendTotal {
      try {
        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            logger.info("Stopping the system")
            httpServerService.stop(maybeServer)
          }
        })
        IO.succeed(())
      } catch {
        case exc: Exception => IO.fail(ShutdownHookRegistrationError(exc))
      }
    }
  }
}
