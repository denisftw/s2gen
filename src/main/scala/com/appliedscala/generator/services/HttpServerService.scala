package com.appliedscala.generator.services

import com.appliedscala.generator.errors.{HttpServerStartException, HttpServerStopError}
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.{DefaultHandler, HandlerList, ResourceHandler}
import org.slf4j.LoggerFactory

/** Created by denis on 9/17/16.
  */
case class HttpServerService() {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private def createServer(path: String, port: Int): Server = {
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

  def start(path: String, port: Int): Either[HttpServerStartException, Server] = {
    try {
      val server = createServer(path, port)
      server.start()
      logger.info(s"The HTTP server has been started on port $port")
      Right(server)
    } catch {
      case th: Throwable => Left(HttpServerStartException(th))
    }
  }

  def stop(maybeServer: Option[Server]): Either[HttpServerStopError, Unit] = {
    try {
      maybeServer.foreach { server =>
        server.stop()
        logger.info("The HTTP server has been stopped")
      }
      Right(())
    } catch {
      case th: Throwable => Left(HttpServerStopError(th))
    }
  }
}
