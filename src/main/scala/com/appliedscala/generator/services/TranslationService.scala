package com.appliedscala.generator.services

import com.appliedscala.generator.model.TranslationBundle
import com.appliedscala.generator.errors.TranslationBuildingError
import zio.Task

import java.io.{File, FileInputStream, InputStreamReader}
import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.util.Properties
import scala.collection.mutable.ListBuffer
import scala.util.Using
import zio.ZIO
import zio.blocking._
import zio.ZManaged
import java.io.IOException
import zio.URIO
import zio.UIO

class TranslationService {
  def buildTranslations(
      i18nDirName: Path, siteDir: Path): ZIO[Blocking, TranslationBuildingError, Seq[TranslationBundle]] = {
    blocking {
      Task {
        if (Files.isDirectory(i18nDirName)) {
          i18nDirName.toFile.listFiles().toSeq
        } else Nil
      }.flatMap { propertyFiles =>
        if (propertyFiles.nonEmpty) {
          ZIO.foreachParN(10)(propertyFiles) { propertyFile =>
            val langCode = propertyFile.getName.split("\\.")(0)
            readPropertiesFile(propertyFile).map { props =>
              if (langCode == "default") {
                TranslationBundle("", props, new File(siteDir.toFile, "").toPath)
              } else {
                TranslationBundle(langCode, props, new File(siteDir.toFile, langCode).toPath)
              }
            }
          }
        } else {
          ZIO.succeed(Seq(TranslationBundle("", Map.empty[String, Object], new File(siteDir.toFile, "").toPath)))
        }
      }.catchAll { case th =>
        ZIO.fail(TranslationBuildingError(th))
      }
    }
  }

  private def readPropertiesFile(propertyFile: File): ZIO[Blocking, Throwable, Map[String, String]] = {
    ZIO.bracket {
      blocking(Task {
        val fileStream = new FileInputStream(propertyFile)
        val properties = new Properties()
        properties.load(new InputStreamReader(fileStream, Charset.forName("UTF-8")))
        (fileStream, properties)
      })
    } { case (fis, _) =>
      URIO(fis.close())
    } { case (_, properties) =>
      import scala.jdk.CollectionConverters.PropertiesHasAsScala
      UIO.succeed(properties.asScala.toMap)
    }
  }
}
