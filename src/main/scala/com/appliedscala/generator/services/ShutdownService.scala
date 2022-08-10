package com.appliedscala.generator.services

import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import zio._
import com.appliedscala.generator.errors._
import java.lang.{Runtime => JavaRuntime}

class ShutdownService(httpServerService: HttpServerService) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerHook(maybeServer: Option[Server]): IO[ShutdownHookRegistrationError, IO[HttpServerStopError, Unit]] = {
    ZIO
      .attemptBlocking {
        ZIO.async[Any, HttpServerStopError, Unit] { register =>
          JavaRuntime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
              logger.info("Stopping the system")
              register.apply(httpServerService.stop(maybeServer))
            }
          })
        }
      }
      .catchAll { th =>
        ZIO.fail(ShutdownHookRegistrationError(th))
      }
  }
}
