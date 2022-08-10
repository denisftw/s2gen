package com.appliedscala.generator.services

import com.appliedscala.generator.model.TranslationBundle
import com.appliedscala.generator.errors.TranslationBuildingError
import zio._

import java.io.{File, FileInputStream, InputStreamReader}
import java.nio.charset.Charset
import java.nio.file.{Files, Path}
import java.util.Properties

class TranslationService {
  def buildTranslations(i18nDirName: Path, siteDir: Path): IO[TranslationBuildingError, Seq[TranslationBundle]] = {
    ZIO
      .blocking {
        val propertyFiles = if (Files.isDirectory(i18nDirName)) {
          i18nDirName.toFile.listFiles().toSeq
        } else Nil
        ZIO.suspendSucceed {
          if (propertyFiles.nonEmpty) {
            ZIO
              .foreachPar(propertyFiles) { propertyFile =>
                val langCode = propertyFile.getName.split("\\.")(0)
                readPropertiesFile(propertyFile).map { props =>
                  if (langCode == "default") {
                    TranslationBundle("", props, new File(siteDir.toFile, "").toPath)
                  } else {
                    TranslationBundle(langCode, props, new File(siteDir.toFile, langCode).toPath)
                  }
                }
              }
              .withParallelism(10)
          } else {
            ZIO.succeed(Seq(TranslationBundle("", Map.empty[String, Object], new File(siteDir.toFile, "").toPath)))
          }
        }
      }
      .catchAll { th =>
        ZIO.fail(TranslationBuildingError(th))
      }
  }

  private def readPropertiesFile(propertyFile: File): IO[Throwable, Map[String, String]] = {
    ZIO.acquireReleaseWith {
      ZIO.attemptBlocking {
        val fileStream = new FileInputStream(propertyFile)
        val properties = new Properties()
        properties.load(new InputStreamReader(fileStream, Charset.forName("UTF-8")))
        (fileStream, properties)
      }
    } { case (fis, _) =>
      ZIO.succeed(fis.close())
    } { case (_, properties) =>
      import scala.jdk.CollectionConverters.PropertiesHasAsScala
      ZIO.succeed(properties.asScala.toMap)
    }
  }
}
