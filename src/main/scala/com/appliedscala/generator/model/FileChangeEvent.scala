package com.appliedscala.generator.model

final case class FileChangeEvent(path: String, action: FileChangeAction, when: Long)