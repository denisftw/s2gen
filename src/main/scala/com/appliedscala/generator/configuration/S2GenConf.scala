package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class S2GenConf(directories: Directories, templates: Templates, site: Site, server: HttpServer)

object S2GenConf {
  implicit val reads: Reads[S2GenConf] = Json.reads[S2GenConf]
}
