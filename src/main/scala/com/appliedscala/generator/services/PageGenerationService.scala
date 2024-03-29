package com.appliedscala.generator.services

import com.appliedscala.generator.model._
import freemarker.template.Template
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import java.io.{File, FileWriter}
import java.nio.file.{Files, Path, Paths}
import java.text.SimpleDateFormat
import java.util.{Date => JavaDate, Map => JavaMap}
import zio._
import com.appliedscala.generator.errors.GenerationError

class PageGenerationService {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val DateFormatter = new SimpleDateFormat("yyyy-MM-dd")
  private val IndexFilename = "index.html"

  def cleanPreviousVersion(archiveOutput: String, indexOutputDir: Path): IO[GenerationError, Unit] = {
    ZIO
      .attemptBlocking {
        logger.info("Cleaning up the previous version")
        FileUtils.deleteDirectory(new File(archiveOutput))
        Files.deleteIfExists(indexOutputDir.resolve(IndexFilename))
      }
      .catchAll { th =>
        ZIO.fail(GenerationError(th))
      }
      .as(())
  }

  def generateArchivePage(
      siteCommonData: Map[String, Object],
      postData: Seq[Map[String, String]],
      archiveOutput: String,
      archiveTemplate: Template,
      translations: Seq[TranslationBundle]
  ): IO[GenerationError, Unit] = {

    ZIO
      .foreach(translations) { langBundle =>
        val inputProps = buildInputProps(siteCommonData, postData, langBundle)
        val langOutputDir = new File(langBundle.siteDir.toFile, archiveOutput)
        if (!langOutputDir.exists()) {
          langOutputDir.mkdirs()
        }
        val archiveOutputFile = new File(langOutputDir, "index.html")
        renderTemplate(archiveOutputFile, archiveTemplate, inputProps).tap { _ =>
          ZIO.succeed(logger.info(s"Successfully generated: <archive> ${langBundle.langCode}"))
        }
      }
      .catchAll(th => ZIO.fail(GenerationError(th)))
      .as(())
  }

  def generateCustomPages(
      siteCommonData: Map[String, Object],
      postData: Seq[Map[String, String]],
      indexOutputDir: Path,
      customTemplateGens: Seq[CustomHtmlTemplateDescription],
      customXmlTemplateDescriptions: Seq[CustomXmlTemplateDescription],
      translations: Seq[TranslationBundle]
  ): IO[GenerationError, Unit] = {

    ZIO
      .foreach(translations) { translationBundle =>
        val inputProps = buildInputProps(siteCommonData, postData, translationBundle)
        val htmlPart = ZIO.blocking {
          ZIO.foreach(customTemplateGens) { gen =>
            val dirName = Paths.get(translationBundle.siteDir.toFile.toString, gen.name)
            if (Files.notExists(dirName)) {
              Files.createDirectories(dirName)
            }
            val indexOutputFile = new File(dirName.toString, IndexFilename)
            renderTemplate(indexOutputFile, gen.template, inputProps).tap { _ =>
              ZIO.succeed(logger.info(s"Successfully generated: <${gen.name}> ${translationBundle.langCode}"))
            }
          }
        }

        val xmlPart = ZIO.blocking {
          ZIO.foreach(customXmlTemplateDescriptions) { gen =>
            val dirName = Paths.get(translationBundle.siteDir.toFile.toString)
            if (Files.notExists(dirName)) {
              Files.createDirectories(dirName)
            }
            val indexOutputFile = new File(dirName.toString, gen.name)
            renderTemplate(indexOutputFile, gen.template, inputProps).tap { _ =>
              ZIO.succeed(logger.info(s"Successfully generated: <${gen.name}> ${translationBundle.langCode}"))
            }
          }
        }
        htmlPart *> xmlPart
      }
      .catchAll(th => ZIO.fail(GenerationError(th)))
      .as(())
  }

  def generateIndexPage(
      siteCommonData: Map[String, Object],
      indexOutputDir: Path,
      indexTemplate: Template,
      translations: Seq[TranslationBundle]
  ): IO[GenerationError, Unit] = {
    ZIO
      .foreach(translations) { langBundle =>
        ZIO.blocking {
          val langOutputDir = langBundle.siteDir.toFile
          if (!langOutputDir.exists()) {
            langOutputDir.mkdir()
          }
          val indexOutputFile = new File(langOutputDir, IndexFilename)
          import scala.jdk.CollectionConverters.MapHasAsJava
          renderTemplate(
            indexOutputFile,
            indexTemplate,
            Map(
              "site" -> siteCommonData.asJava,
              "messages" -> langBundle.messages.asJava,
              "currentLanguage" -> langBundle.langCode
            ).asJava
          ).tap { _ =>
            ZIO.succeed(logger.info(s"Successfully generated: <index> ${langBundle.langCode}"))
          }
        }
      }
      .catchAll(th => ZIO.fail(GenerationError(th)))
      .as(())
  }

  def generatePostPages(
      postData: Seq[Map[String, String]],
      siteCommonData: Map[String, Object],
      outputPaths: OutputPaths,
      htmlTemplates: HtmlTemplates,
      translations: Seq[TranslationBundle]
  ): IO[GenerationError, Unit] = {
    ZIO
      .foreach(translations) { langBundle =>
        ZIO.blocking {
          val langOutputDir = langBundle.siteDir.toFile
          if (!langOutputDir.exists()) {
            langOutputDir.mkdir()
          }
          val onlyPosts = postData.filter(_.get("type").contains("post"))
          ZIO.foreach(onlyPosts) { contentObj =>
            generateSingleBlogFile(
              siteCommonData,
              contentObj,
              langOutputDir.toString,
              htmlTemplates.postTemplate,
              langBundle
            )
          }
        }
      }
      .as(())
  }

  def addBuildDateInformation(settingsCommonData: Map[String, Object]): Map[String, Object] = {
    settingsCommonData + ("lastBuildDateJ" -> new JavaDate())
  }

  private def addJavaDate(post: Map[String, String]): Map[String, Object] = {
    val dateStr = post("date")
    val dateJ = DateFormatter.parse(dateStr)
    post + ("dateJ" -> dateJ)
  }

  private def generateSingleBlogFile(
      siteCommonData: Map[String, Object],
      contentObj: Map[String, String],
      globalOutputDir: String,
      template: Template,
      langBundle: TranslationBundle
  ): IO[GenerationError, Unit] = {
    val sourceFilename = contentObj("sourceFilename")
    ZIO
      .blocking {
        val outputLink = contentObj.getOrElse(
          "link",
          throw new Exception(s"The required link property is not specified for $sourceFilename")
        )

        val maybeLanguage = contentObj.get("language")
        if (
          (maybeLanguage.isDefined && maybeLanguage.contains(langBundle.langCode)) ||
          (maybeLanguage.isEmpty && langBundle.langCode.length == 0)
        ) {
          val linkLastPart = outputLink.split("/").last
          val (outputDir, outputFilename) = if (linkLastPart.endsWith(".html")) {
            val localOutputDir = Paths.get(globalOutputDir, Paths.get(outputLink).getParent.toString)
            val outputFilename = linkLastPart
            (localOutputDir, outputFilename)
          } else {
            val localOutputDir = Paths.get(globalOutputDir, outputLink)
            val outputFilename = "index.html"
            (localOutputDir, outputFilename)
          }

          if (Files.notExists(outputDir)) {
            Files.createDirectories(outputDir)
          }

          import scala.jdk.CollectionConverters.MapHasAsJava
          val input = Map(
            "content" -> addJavaDate(contentObj).asJava,
            "site" -> siteCommonData.asJava,
            "messages" -> langBundle.messages.asJava,
            "currentLanguage" -> langBundle.langCode
          ).asJava
          val outputFile = new File(outputDir.toFile, outputFilename)
          renderTemplate(outputFile, template, input).tap { _ =>
            ZIO.succeed(logger.info(s"Successfully generated: $sourceFilename ${langBundle.langCode}"))
          }
        } else ZIO.unit
      }
      .catchAll(th => ZIO.fail(GenerationError(th)))
      .as(())
  }

  private def renderTemplate(outputFile: File, template: Template, input: java.util.Map[String, _]): Task[Unit] = {
    ZIO.blocking {
      ZIO.acquireReleaseWith {
        ZIO.succeed(new FileWriter(outputFile))
      }(fileWriter => ZIO.succeed(fileWriter.close())) { fileWriter =>
        ZIO.succeed(template.process(input, fileWriter))
      }
    }
  }

  private def buildInputProps(
      siteCommonData: Map[String, Object],
      postData: Seq[Map[String, String]],
      translationBundle: TranslationBundle
  ): JavaMap[String, Object] = {
    val (publishedPosts, miscArticles) = postData
      .filter { post =>
        val postStatus = post.get("status")
        postStatus.contains("published")
      }
      .partition { post =>
        val postType = post.get("type")
        postType.contains("post")
      }
    val allPosts = publishedPosts
      .map { post =>
        import scala.jdk.CollectionConverters.MapHasAsJava
        addJavaDate(post).asJava
      }
      .sortWith(_.get("dateJ").asInstanceOf[JavaDate].getTime > _.get("dateJ").asInstanceOf[JavaDate].getTime)
    import scala.jdk.CollectionConverters.MapHasAsJava
    val allMiscPosts = miscArticles.groupBy(_("title")).map { case (key, translations) =>
      val byTr = translations
        .groupBy { obj =>
          obj.getOrElse("language", "")
        }
        .map { case (key2, red) =>
          (key2, red.head.asJava)
        }
      (key, byTr.asJava)
    }
    val lastUpdated = allPosts.headOption.map(_.get("dateJ").asInstanceOf[JavaDate]).getOrElse(new JavaDate())
    import scala.jdk.CollectionConverters.SeqHasAsJava
    val blogPosts = allPosts.asJava
    import scala.jdk.CollectionConverters.MapHasAsJava
    val miscPosts = allMiscPosts.asJava
    val inputProps = Map(
      "posts" -> blogPosts,
      "misc" -> miscPosts,
      "site" -> (siteCommonData ++ Map("lastPubDateJ" -> lastUpdated)).asJava,
      "messages" -> (translationBundle.messages).asJava,
      "currentLanguage" -> translationBundle.langCode
    ).asJava
    inputProps
  }
}
