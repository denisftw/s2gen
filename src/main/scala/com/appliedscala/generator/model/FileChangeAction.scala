package com.appliedscala.generator.model

import enumeratum._

sealed trait FileChangeAction extends EnumEntry

object FileChangeAction extends Enum[FileChangeAction] {
  val values = findValues
  case object Deleted extends FileChangeAction
  case object Created extends FileChangeAction
  case object Updated extends FileChangeAction
}
