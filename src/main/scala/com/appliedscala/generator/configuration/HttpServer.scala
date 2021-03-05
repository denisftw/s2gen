package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class HttpServer(port: Int)

object HttpServer {
  implicit val reads: Reads[HttpServer] = Json.reads[HttpServer]
}
