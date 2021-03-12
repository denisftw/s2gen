package com.appliedscala.generator.services

import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import zio.blocking._
import zio.ZIO
import zio.Task
import com.appliedscala.generator.errors._

class ShutdownService(httpServerService: HttpServerService) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerHook(maybeServer: Option[Server])
      : ZIO[Blocking, ShutdownHookRegistrationError, ZIO[Blocking, HttpServerStopError, Unit]] = {
    blocking {
      Task {
        ZIO.effectAsync[Blocking, HttpServerStopError, Unit] { register =>
          Runtime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
              logger.info("Stopping the system")
              register.apply(httpServerService.stop(maybeServer))
            }
          })
        }
      }.refineOrDie { case th: Throwable =>
        ShutdownHookRegistrationError(th)
      }
    }
  }
}
