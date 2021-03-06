package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class ApplicationConfiguration(directories: Directories, templates: Templates, site: Site, server: HttpServer)

object ApplicationConfiguration {
  implicit val reads: Reads[ApplicationConfiguration] = Json.reads[ApplicationConfiguration]
}
