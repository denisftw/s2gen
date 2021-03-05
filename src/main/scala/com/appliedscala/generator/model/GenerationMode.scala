package com.appliedscala.generator.model

import enumeratum._

sealed trait GenerationMode extends EnumEntry

object GenerationMode extends Enum[GenerationMode] {
  val values = findValues
  case object Once extends GenerationMode
  case object Monitor extends GenerationMode
  case object MonitorNoServer extends GenerationMode
}
