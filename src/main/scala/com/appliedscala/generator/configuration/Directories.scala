package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class Directories(basedir: String, content: String, output: String, archive: String, templates: String)

object Directories {
  implicit val reads: Reads[Directories] = Json.reads[Directories]
}
