package com.appliedscala.generator.services

import com.appliedscala.generator.errors.{ApplicationError, CommandLineError}
import com.appliedscala.generator.model.{CommandLineOption, CustomHtmlTemplateDescription, GenerationMode}
import org.apache.commons.cli.{DefaultParser, HelpFormatter, Options}
import zio._

import scala.util.{Success, Try}

class CommandLineService(initService: InitService) {

  def parseCommandLineArgs(args: Array[String]): IO[ApplicationError, Either[Unit, GenerationMode]] = {
    ZIO.suspendSucceed {
      val options = new Options
      CommandLineOption.values.foreach { option =>
        options.addOption(option.option, false, option.description)
      }
      val helpFormatter = new HelpFormatter
      val parser = new DefaultParser
      val cmd = parser.parse(options, args)

      if (CommandLineOption.Version.matches(cmd)) {
        val versionNumberT = Try { CustomHtmlTemplateDescription.getClass.getPackage.getImplementationVersion }
        val versionNumber = versionNumberT match {
          case Success(version) if version != null => version
          case _                                   => "[dev]"
        }
        println(s"""s2gen version $versionNumber""")
        ZIO.succeed(Left(()))
      } else if (CommandLineOption.Init.matches(cmd)) {
        initService.initProjectStructure().map(_ => Left(()))
      } else if (CommandLineOption.Help.matches(cmd)) {
        helpFormatter.printHelp("s2gen", options)
        ZIO.succeed(Left(()))
      } else if (CommandLineOption.Once.matches(cmd)) {
        ZIO.succeed(Right(GenerationMode.Once))
      } else if (CommandLineOption.NoServer.matches(cmd)) {
        ZIO.succeed(Right(GenerationMode.MonitorNoServer))
      } else if (cmd.getOptions.isEmpty) {
        ZIO.succeed(Right(GenerationMode.Monitor))
      } else {
        ZIO.fail(CommandLineError(s"Unrecognized option: ${cmd.getOptions.head.getOpt}"))
      }
    }
  }
}
