package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class Templates(post: String, archive: String, index: String, custom: Seq[String], customXml: Seq[String])
object Templates {
  implicit val reads: Reads[Templates] = Json.reads[Templates]
}
