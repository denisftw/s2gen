package com.appliedscala.generator.services

import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors.ConfigurationError
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsError, JsSuccess, Json}
import zio._

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.util.Using

class ConfigurationReadingService {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val confFileName = "s2gen.json"

  def readConfiguration(): IO[ConfigurationError, ApplicationConfiguration] = ZIO.suspendSucceed {
    if (!Files.exists(Paths.get(confFileName))) {
      ZIO.fail(ConfigurationError(s"Cannot find a configuration file '$confFileName'"))
    } else {
      val confStr = Using.resource(Source.fromFile(confFileName))(_.getLines().mkString(""))
      Json.parse(confStr).validate[ApplicationConfiguration] match {
        case JsSuccess(value, _) => ZIO.succeed(value)
        case error: JsError =>
          val errorMessage = JsError.Message.unapply(error).getOrElse("unknown error")
          ZIO.fail(ConfigurationError(s"Cannot parse configuration file: $errorMessage"))
      }
    }
  }
}
