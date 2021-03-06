package com.appliedscala.generator.services

import com.appliedscala.generator.model.TranslationBundle
import zio.Task

import java.io.{File, FileInputStream, InputStreamReader}
import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.util.Properties
import scala.collection.mutable.ListBuffer
import scala.util.Using

class TranslationService {

  def buildTranslationsZ(i18nDirName: Path, siteDir: Path): Task[Seq[TranslationBundle]] = {
    Task {
      buildTranslations(i18nDirName, siteDir)
    }
  }

  def buildTranslations(i18nDirName: Path, siteDir: Path): Seq[TranslationBundle] = {
    val i18nListBuffer = new ListBuffer[TranslationBundle]
    if (Files.isDirectory(i18nDirName)) {
      i18nDirName.toFile.listFiles().map { propertyFile =>
        val langCode = propertyFile.getName.split("\\.")(0)
        val props = readPropertiesFile(propertyFile)
        if (langCode == "default") {
          i18nListBuffer += TranslationBundle("", props, new File(siteDir.toFile, "").toPath)
        } else {
          i18nListBuffer += TranslationBundle(langCode, props, new File(siteDir.toFile, langCode).toPath)
        }
      }
    }
    val result = i18nListBuffer.result()
    if (result.isEmpty) Seq(TranslationBundle("", Map.empty[String, Object], new File(siteDir.toFile, "").toPath))
    else result
  }

  private def readPropertiesFile(propertyFile: File): Map[String, String] = {
    Using.resource(new FileInputStream(propertyFile)) { fileStream =>
      val prop = new Properties()
      prop.load(new InputStreamReader(fileStream, Charset.forName("UTF-8")))
      import scala.jdk.CollectionConverters.PropertiesHasAsScala
      prop.asScala.toMap
    }
  }
}
