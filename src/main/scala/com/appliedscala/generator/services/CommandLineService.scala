package com.appliedscala.generator.services

import com.appliedscala.generator.model.{CommandLineOption, CustomHtmlTemplateDescription, GenerationMode}
import org.apache.commons.cli.{DefaultParser, HelpFormatter, Options}

import scala.util.Try

class CommandLineService(initService: InitService) {

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
