package com.appliedscala.generator.model

import enumeratum._
import org.apache.commons.cli.CommandLine

sealed abstract class CommandLineOption(val option: String, val description: String) extends EnumEntry {
  def matches(cmd: CommandLine): Boolean = cmd.hasOption(option)
}

object CommandLineOption extends Enum[CommandLineOption] {
  val values = findValues
  case object Version extends CommandLineOption("version", "print version information")
  case object Init extends CommandLineOption("init", "initialize project structure and exit")
  case object Help extends CommandLineOption("help", "print this message")
  case object Once extends CommandLineOption("once", "generate the site once and exit without starting the monitoring")
  case object NoServer extends CommandLineOption("noserver", "start monitoring without the embedded server")
}
