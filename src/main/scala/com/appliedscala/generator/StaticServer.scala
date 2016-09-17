package com.appliedscala.generator

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.{DefaultHandler, HandlerList, ResourceHandler}
import org.slf4j.LoggerFactory

/**
  * Created by denis on 9/17/16.
  */
case class StaticServer(path: String, port: Int, fakeServer: Boolean) {
  val logger = LoggerFactory.getLogger("S2HttpServer")
  val server = {
    if (fakeServer) {
      None
    } else {
      logger.info(s"Starting the HTTP server")
      val jettyServer = new Server(port)
      val resource_handler = new ResourceHandler()
      resource_handler.setWelcomeFiles(Array("index.html"))
      resource_handler.setResourceBase(path)
      resource_handler.setDirectoriesListed(true)
      val handlers = new HandlerList()
      handlers.setHandlers(Array(resource_handler, new DefaultHandler()))
      jettyServer.setHandler(handlers)
      Some(jettyServer)
    }

  }

  def start(): Unit = {
    try {
      server.foreach {
        s => s.start()
        logger.info(s"The HTTP server has been started on port $port")
      }
    } catch {
    case e: Exception =>
      logger.error("Error occurred while trying to start the HTTP server", e)
    }
  }

  def stop(): Unit = {
    try {
      server.foreach { s =>
        s.stop()
        logger.info("The HTTP server has been stopped")
      }
    } catch {
      case e: Exception =>
        logger.error("Error occurred while trying to stop the HTTP server", e)
    }
  }
}
