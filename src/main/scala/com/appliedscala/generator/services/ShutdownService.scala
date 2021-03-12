package com.appliedscala.generator.services

import com.appliedscala.generator.errors.ShutdownHookRegistrationError
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import zio.blocking._
import zio.ZIO
import zio.Task
import zio.UIO
import com.appliedscala.generator.errors.SystemError

class ShutdownService(httpServerService: HttpServerService) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerHook(maybeServer: Option[Server], stopMonitorCallback: () => UIO[_])
      : ZIO[Blocking, ShutdownHookRegistrationError, Unit] = {
    blocking {
      Task {
        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            logger.info("Stopping the system")
            val stopRoutine = httpServerService.stop(maybeServer) *> stopMonitorCallback()
            zio.Runtime.global.unsafeRunSync(stopRoutine)
          }
        })
      }.refineOrDie { case th: Throwable =>
        ShutdownHookRegistrationError(th)
      }
    }
  }
}
