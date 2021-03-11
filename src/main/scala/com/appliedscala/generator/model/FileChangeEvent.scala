package com.appliedscala.generator.model

import java.nio.file.Path

final case class FileChangeEvent(path: Path, action: FileChangeAction, when: Long)