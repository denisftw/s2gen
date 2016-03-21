package com.appliedscala.generator

import java.io.{FileWriter, OutputStreamWriter, File}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._
import java.util.Locale
import akka.actor.ActorSystem
import com.typesafe.config.impl.{AbstractConfigValue, ConfigString}
import com.typesafe.config._
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import freemarker.template.{Template, TemplateExceptionHandler, Version, Configuration}
import org.pegdown.{Extensions, PegDownProcessor}
import org.pegdown.plugins.PegDownPlugins

import scala.io.Source
import scala.collection.JavaConversions._
import scalaz.{Validation, \/}
import scalaz.concurrent.Task
import scala.concurrent.ExecutionContext.Implicits.global
import com.beachape.filemanagement.RxMonitor
import java.nio.file.StandardWatchEventKinds._

case class Content(title: String, body: String, date: String)

case class FileRenderingTask(filename: String, task: Task[Unit])

object SiteGenerator {

  val logger = LoggerFactory.getLogger("SiteGenerator")
  val PropertiesSeparator = "~~~~~~"
  val DefaultConfFile = "conf/application.conf"
  val SiteMapFilename = "sitemap.xml"
  val IndexFilename = "index.html"

  def main(args: Array[String]) = {
    implicit val actorSystem = ActorSystem.create("actor-world")

    val conf = ConfigFactory.parseFile(new File(DefaultConfFile))
    val baseDir = conf.getString("directories.basedir")
    val contentDir = conf.getString("directories.content")
    val relativeSiteDir = conf.getString("directories.output")
    val relativeTemplatesDirName = conf.getString("directories.templates")
    val postTemplateName = conf.getString("templates.post")
    val archiveTemplateName = conf.getString("templates.archive")
    val sitemapTemplateName = conf.getString("templates.sitemap")
    val indexTemplateName = conf.getString("templates.index")
    val siteHost = conf.getString("site.host")
    val lastmod = conf.getString("site.lastmod")

    val siteDir = Paths.get(baseDir, relativeSiteDir).toString
    val templatesDirName = Paths.get(baseDir, relativeTemplatesDirName).toString
    val archiveOutput = Paths.get(siteDir, conf.getString("directories.archive"))
    val sitemapOutputDir = Paths.get(siteDir)
    val indexOutputDir = Paths.get(siteDir)
    val contentDirFile = Paths.get(baseDir, contentDir).toFile

    val pgPluginsCode = Extensions.TABLES | Extensions.FENCED_CODE_BLOCKS
    val mdGenerator = new PegDownProcessor(pgPluginsCode)
    val cfg = createFreemarkerConfig(templatesDirName)
    val postTemplate = cfg.getTemplate(postTemplateName)
    val archiveTemplate = cfg.getTemplate(archiveTemplateName)
    val sitemapTemplate = cfg.getTemplate(sitemapTemplateName)
    val indexTemplate = cfg.getTemplate(indexTemplateName)
    val mdContentFiles = recursiveListFiles(contentDirFile).filterNot(_.isDirectory)

    def regenerate(): Unit = {
      logger.info("Cleaning previous version of the site")
      FileUtils.deleteDirectory(archiveOutput.toFile)
      Files.deleteIfExists(Paths.get(sitemapOutputDir.toString, SiteMapFilename))
      Files.deleteIfExists(Paths.get(indexOutputDir.toString, IndexFilename))

      logger.info("Generation started")
      val postData = mdContentFiles.map { mdFile =>
        processMdFile(mdFile, mdGenerator)
      }

      generateArchivePage(postData, archiveOutput, archiveTemplate)
      generateSitemap(postData, sitemapOutputDir, sitemapTemplate, Map("siteHost" -> siteHost, "lastmod" -> lastmod))
      generateIndexPage(indexOutputDir, indexTemplate)

      val tasks = postData.map { contentObj =>
        writeRenderedFile(contentObj, siteDir, postTemplate)
      }
      runTasks(tasks)
      logger.info("Generation finished")
    }

    regenerate()

    logger.info("Registering a file watcher")

    val monitor = RxMonitor()
    val observable = monitor.observable
    val subscription = observable.subscribe(
      onNext = { pathEvent =>
        logger.info(s"A markdown file has been changed, regenerating the HTML")
        regenerate()
      },
      onError = { exc => logger.error("Exception occurred", exc) },
      onCompleted = { () => logger.info("Monitor has been shut down") }
    )

    Files.walkFileTree(contentDirFile.toPath, new SimpleFileVisitor[Path]() {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          monitor.registerPath(ENTRY_MODIFY, dir)
          FileVisitResult.CONTINUE
        }
    })
    monitor.registerPath(ENTRY_MODIFY, Paths.get(templatesDirName))

    logger.info(s"Waiting for changes under the content directory: $contentDir")
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        logger.info(s"Stopping the monitor")
        monitor.stop()
      }
    })
  }

  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }

  private def createFreemarkerConfig(templateDirName: String): Configuration = {
    val cfg = new Configuration(Configuration.VERSION_2_3_20)
    cfg.setDirectoryForTemplateLoading(new File(templateDirName))
    cfg.setDefaultEncoding("UTF-8")
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER)
    cfg
  }

  private def generateSitemap(postData: Seq[Map[String, String]], currentOutputDir: Path,
                              sitemapTemplate: Template, additionalMap: Map[String, String]): Unit = {
    logger.info("Generating the sitemap")
    val publishedPosts = postData.filter { post =>
      val postStatus = post.get("status")
      postStatus.contains("published")
    }
    val posts = seqAsJavaList(publishedPosts.map { post => mapAsJavaMap(post) }.sortWith(_.get("date") > _.get("date")))
    val inputProps = mapAsJavaMap { additionalMap ++ Map("posts" -> posts ) }
    if (Files.notExists(currentOutputDir)) {
      Files.createDirectories(currentOutputDir)
    }
    val sitemapOutputFile = new File(currentOutputDir.toFile, SiteMapFilename)
    renderTemplate(sitemapOutputFile, sitemapTemplate, inputProps)
    logger.info("The sitemap was generated")
  }

  private def generateIndexPage(indexOutputDir: Path, indexTemplate: Template): Unit = {
    logger.info("Generating the index page")
    val indexOutputFile = new File(indexOutputDir.toFile, IndexFilename)
    renderTemplate(indexOutputFile, indexTemplate, mapAsJavaMap(Map.empty[String, String]))
    logger.info("The index page was generated")
  }

  private def generateArchivePage(postData: Seq[Map[String, String]], archiveOutput: Path, archiveTemplate: Template): Unit = {
    logger.info("Generating the archive page")
    val publishedPosts = postData.filter { post =>
      val postStatus = post.get("status")
      postStatus.contains("published")
    }
    val posts = seqAsJavaList(publishedPosts.map { post => mapAsJavaMap(post) }.sortWith(_.get("date") > _.get("date")))
    val archiveInput = mapAsJavaMap { Map("posts" -> posts ) }
    if (Files.notExists(archiveOutput)) {
      Files.createDirectories(archiveOutput)
    }
    val archiveOutputFile = new File(archiveOutput.toFile, "index.html")
    renderTemplate(archiveOutputFile, archiveTemplate, archiveInput)
    logger.info("The archive page was generated")
  }

  private def processMdFile(mdFile: File, mdGenerator: PegDownProcessor): Map[String, String] = {
    val postContent = Source.fromFile(mdFile).getLines().toList
    val separatorLineNumber = postContent.indexWhere(_.startsWith(PropertiesSeparator))
    val propertiesLines = postContent.take(separatorLineNumber)
    val contentLines = postContent.drop(separatorLineNumber + 1)

    val contentPropertyMap = propertiesLines.flatMap { propertyLine =>
      val pair = propertyLine.split("=")
      pair match {
        case Array(first, second) => Some(pair(0) -> pair(1))
        case _ => None
      }
    }.toMap
    val mdContent = contentLines.mkString("\n")
    val renderedMdContent = mdGenerator.markdownToHtml(mdContent)

    val contentObj = contentPropertyMap ++ Map(
      "body" -> renderedMdContent,
      "sourceDirectoryPath" -> mdFile.getParentFile.getAbsolutePath,
      "sourceFilename" -> mdFile.getAbsolutePath,
      "uri" -> contentPropertyMap("link")
    )
    contentObj
  }

  private def writeRenderedFile(contentObj: Map[String, String],
                                globalOutputDir: String, template: Template): FileRenderingTask = {
    val sourceFilename = contentObj("sourceFilename")
    val task = Task {
      val outputLink = contentObj.getOrElse("link", throw new Exception(
        s"The required link property is not specified for $sourceFilename"))

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

      val input = mapAsJavaMap { Map("content" -> mapAsJavaMap(contentObj) ) }
      val outputFile = new File(outputDir.toFile, outputFilename)
      renderTemplate(outputFile, template, input)
    }
    FileRenderingTask(sourceFilename, task)
  }

  private def renderTemplate(outputFile: File, template: Template, input: java.util.Map[String, _]): Unit = {
    val fileWriter = new FileWriter(outputFile)
    try {
      template.process(input, fileWriter)
    } finally {
      fileWriter.close()
    }
  }

  private def runTasks(tasks: Seq[FileRenderingTask]): Unit = {
    val results = tasks.foreach { work =>
      val filename = work.filename
      val result = work.task.unsafePerformSyncAttempt
      result.fold(
        th => logger.error(s"Exception occurred while generating the following: $filename", th),
        res => logger.info(s"Successfully generated: $filename")
      )
    }
  }
}
