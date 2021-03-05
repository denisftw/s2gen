package com.appliedscala.generator.model

import java.nio.file.Path

case class TranslationBundle(langCode: String, messages: Map[String, Object], siteDir: Path)
