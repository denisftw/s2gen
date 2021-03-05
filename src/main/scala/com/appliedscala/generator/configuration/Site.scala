package com.appliedscala.generator.configuration

import play.api.libs.json.{Json, Reads}

case class Site(title: String, description: String, host: String, lastmod: String)

object Site {
  implicit val reads: Reads[Site] = Json.reads[Site]
}
