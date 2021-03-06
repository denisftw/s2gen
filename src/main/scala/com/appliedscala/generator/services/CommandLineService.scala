package com.appliedscala.generator.services

import com.appliedscala.generator.errors.CommandLineError
import com.appliedscala.generator.model.{CommandLineOption, CustomHtmlTemplateDescription, GenerationMode}
import org.apache.commons.cli.{DefaultParser, HelpFormatter, Options}
import zio.IO

import scala.util.Try

class CommandLineService(initService: InitService) {

  def parseCommandLineArgsZ(args: Array[String]): IO[CommandLineError, Either[Unit, GenerationMode]] =
    IO.effectSuspendTotal {
      val options = new Options
      CommandLineOption.values.foreach { option =>
        options.addOption(option.option, false, option.description)
      }
      val helpFormatter = new HelpFormatter
      val parser = new DefaultParser
      val cmd = parser.parse(options, args)

      if (CommandLineOption.Version.matches(cmd)) {
        val versionNumberT = Try { CustomHtmlTemplateDescription.getClass.getPackage.getImplementationVersion }
        val versionNumber = versionNumberT.getOrElse("[dev]")
        println(s"""s2gen version $versionNumber""")
        IO.succeed(Left(()))
      } else if (CommandLineOption.Init.matches(cmd)) {
        initService.initProjectStructure()
        IO.succeed(Left(()))
      } else if (CommandLineOption.Help.matches(cmd)) {
        helpFormatter.printHelp("s2gen", options)
        IO.succeed(Left(()))
      } else if (CommandLineOption.Once.matches(cmd)) {
        IO.succeed(Right(GenerationMode.Once))
      } else if (CommandLineOption.NoServer.matches(cmd)) {
        IO.succeed(Right(GenerationMode.MonitorNoServer))
      } else if (cmd.getOptions.isEmpty) {
        IO.succeed(Right(GenerationMode.Monitor))
      } else {
        IO.fail(CommandLineError(s"Unrecognized option: ${cmd.getOptions.head.getOpt}"))
      }
    }

  def parseCommandLineArgs(args: Array[String]): Either[Unit, GenerationMode] = {
    val options = new Options
    CommandLineOption.values.foreach { option =>
      options.addOption(option.option, false, option.description)
    }
    val helpFormatter = new HelpFormatter
    val parser = new DefaultParser
    val cmd = parser.parse(options, args)

    if (CommandLineOption.Version.matches(cmd)) {
      val versionNumberT = Try { CustomHtmlTemplateDescription.getClass.getPackage.getImplementationVersion }
      val versionNumber = versionNumberT.getOrElse("[dev]")
      println(s"""s2gen version $versionNumber""")
      Left(())
    } else if (CommandLineOption.Init.matches(cmd)) {
      initService.initProjectStructure()
      Left(())
    } else if (CommandLineOption.Help.matches(cmd)) {
      helpFormatter.printHelp("s2gen", options)
      Left(())
    } else if (CommandLineOption.Once.matches(cmd)) {
      Right(GenerationMode.Once)
    } else {
      if (CommandLineOption.NoServer.matches(cmd)) {
        Right(GenerationMode.MonitorNoServer)
      } else {
        Right(GenerationMode.Monitor)
      }
    }
  }
}
