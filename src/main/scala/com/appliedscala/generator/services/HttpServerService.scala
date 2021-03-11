package com.appliedscala.generator.services

import com.appliedscala.generator.errors.{HttpServerStartError, HttpServerStopError}
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.{DefaultHandler, HandlerList, ResourceHandler}
import org.slf4j.LoggerFactory
import zio.IO
import zio.Task
import zio.blocking._
import zio.ZIO

case class HttpServerService() {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def createServer(path: String, port: Int): Task[Server] = Task {
    logger.info(s"Starting the HTTP server")
    val jettyServer = new Server(port)
    val resourceHandler = new ResourceHandler()
    resourceHandler.setWelcomeFiles(Array("index.html"))
    resourceHandler.setResourceBase(path)
    resourceHandler.setDirectoriesListed(true)
    val handlers = new HandlerList()
    handlers.setHandlers(Array(resourceHandler, new DefaultHandler()))
    jettyServer.setHandler(handlers)
    jettyServer
  }

  def start(path: String, port: Int): ZIO[Blocking, HttpServerStartError, Server] = {
    blocking {
      createServer(path, port).map { server =>
        server.start()
        logger.info(s"The HTTP server has been started on port $port")
        server
      }
    }.refineOrDie { case th: Throwable => 
      HttpServerStartError(th)
    }
  }

  def stop(maybeServer: Option[Server]): ZIO[Blocking, HttpServerStopError, Unit] = {
    blocking {
      Task {
        maybeServer.foreach { server =>
          server.stop()
          logger.info("The HTTP server has been stopped")
        }
      }
    }.refineOrDie { case th: Throwable =>
      HttpServerStopError(th)
    }
  }
}
