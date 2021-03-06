package com.appliedscala.generator.services

import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors.ConfigurationError
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsError, JsSuccess, Json}
import zio.IO

import java.nio.file.{Files, Paths}
import scala.io.Source
import scala.util.Using

class ConfigurationReadingService {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val confFileName = "s2gen.json"

  def readConfiguration(): IO[ConfigurationError, ApplicationConfiguration] = IO.effectSuspendTotal {
    if (!Files.exists(Paths.get(confFileName))) {
      IO.fail(ConfigurationError(s"Cannot find a configuration file '$confFileName'"))
    } else {
      val confStr = Using.resource(Source.fromFile(confFileName))(_.getLines().mkString(""))
      Json.parse(confStr).validate[ApplicationConfiguration] match {
        case JsSuccess(value, _) => IO.succeed(value)
        case error: JsError =>
          val errorMessage = JsError.Message.unapply(error).getOrElse("unknown error")
          IO.fail(ConfigurationError(s"Cannot parse configuration file: $errorMessage"))
      }
    }
  }

  def parseConfigOrExit(): ApplicationConfiguration = {
    if (!Files.exists(Paths.get(confFileName))) {
      System.err.println(s"Cannot find a configuration file '$confFileName'")
      System.exit(-1)
    }

    val confStr = Using.resource(Source.fromFile(confFileName))(_.getLines().mkString(""))
    Json.parse(confStr).validate[ApplicationConfiguration] match {
      case JsSuccess(value, _) => value
      case error: JsError =>
        val errorMessage = JsError.Message.unapply(error).getOrElse("unknown error")
        val exception = new ConfigurationError(errorMessage)
        logger.error("Error occurred while parsing the configuration file", exception)
        System.exit(-1)
        throw new RuntimeException
    }
  }
}
