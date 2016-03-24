package com.appliedscala.generator

import java.io.{PrintWriter, FileOutputStream, FileWriter, File}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._
import akka.actor.ActorSystem
import com.typesafe.config._
import org.apache.commons.cli.{HelpFormatter, DefaultParser, Options}
import org.apache.commons.io.{Charsets, IOUtils, FileUtils}
import org.pegdown.LinkRenderer.Rendering
import org.pegdown.ast.ExpLinkNode
import org.slf4j.LoggerFactory

import freemarker.template.{Template, TemplateExceptionHandler, Configuration}
import org.pegdown._

import scala.io.Source
import scala.collection.JavaConversions._
import scala.util.Try
import scalaz.{Validation, \/}
import scalaz.concurrent.Task
import scala.concurrent.ExecutionContext.Implicits.global
import com.beachape.filemanagement.RxMonitor
import java.nio.file.StandardWatchEventKinds._

case class FileRenderingTask(filename: String, task: Task[Unit])

object SiteGenerator {

  val logger = LoggerFactory.getLogger("SiteGenerator")
  val PropertiesSeparator = "~~~~~~"
  val DefaultConfFile = "s2gen.conf"
  val SiteMapFilename = "sitemap.xml"
  val IndexFilename = "index.html"

  val OptionVersion = "version"
  val InitOption = "init"
  val HelpOption = "help"

  def main(args: Array[String]) = {

    val options = new Options
    options.addOption(OptionVersion, false, "print the version information")
    options.addOption(InitOption, false, "initialize project structure and exit")
    options.addOption(HelpOption, false, "print this message")
    val helpFormatter = new HelpFormatter
    val parser = new DefaultParser
    val cmd = parser.parse(options, args)

    if (cmd.hasOption(OptionVersion)) {
      val versionNumberT = Try { FileRenderingTask.getClass.getPackage.getImplementationVersion }
      val versionNumber = versionNumberT.getOrElse("[dev]")
      println(s"""s2gen version $versionNumber""")
      System.exit(0)
    } else if (cmd.hasOption(InitOption)) {
      initProjectStructure()
      System.exit(0)
    } else if (cmd.hasOption(HelpOption)) {
      helpFormatter.printHelp("s2gen", options)
      System.exit(0)
    }

    if (!Files.exists(Paths.get(DefaultConfFile))) {
      System.err.println(s"Cannot find a configuration file $DefaultConfFile")
      System.exit(-1)
    }

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
    val siteTitle = conf.getString("site.title")
    val siteDescription = conf.getString("site.description")
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
    val linkRenderer = createLinkRenderer(siteHost)
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

      val siteCommonData = Map("title" -> siteTitle, "description" -> siteDescription)
      logger.info("Generation started")
      val postData = mdContentFiles.map { mdFile =>
        processMdFile(mdFile, mdGenerator, linkRenderer)
      }

      generateArchivePage(siteCommonData, postData, archiveOutput, archiveTemplate)
      generateSitemap(siteCommonData, postData, sitemapOutputDir, sitemapTemplate, Map("siteHost" -> siteHost, "lastmod" -> lastmod))
      generateIndexPage(siteCommonData, indexOutputDir, indexTemplate)

      val tasks = postData.map { contentObj =>
        generateSingleBlogFile(siteCommonData, contentObj, siteDir, postTemplate)
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

    logger.info(s"Waiting for changes...")
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        logger.info(s"Stopping the monitor")
        monitor.stop()
      }
    })
  }

  private def initProjectStructure(): Unit = {
    println("Initializing...")
    val classLoader = this.getClass.getClassLoader
    copyFromClasspath(classLoader, "init/site/styles.css", "site/css", "styles.css")
    copyFromClasspath(classLoader, "init/s2gen.conf", ".", "s2gen.conf")
    copyFromClasspath(classLoader, "init/content/hello-world.md", "content/blog/2016", "hello-world.md")
    val templateNames = Seq("archive.ftl", "blog.ftl", "footer.ftl" , "header.ftl", "index.ftl",
      "main.ftl", "menu.ftl", "page.ftl", "post.ftl", "sitemap.ftl")
    templateNames.foreach { templateName =>
      copyFromClasspath(classLoader, s"init/templates/$templateName", "templates", templateName)
    }
    println(s"The skeleton project has been generated. Now you can type s2gen to generate HTML files")
  }

  private def copyFromClasspath(classLoader: ClassLoader, cpPath: String,
                      destinationDir: String, destinationFilename: String): Unit = {
    val source = Source.fromInputStream(classLoader.getResourceAsStream(cpPath), "UTF-8")
    val destinationPath = Paths.get(destinationDir)
    if (Files.notExists(destinationPath)) {
      Files.createDirectories(destinationPath)
    }
    val pw = new PrintWriter(new File(destinationDir, destinationFilename))
    try {
      source.foreach( char => pw.print(char) )
    } catch {
      case e: Exception => logger.error("Exception occurred while initializing the project", e)
    } finally {
      pw.close()
    }
  }

  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }

  private def createLinkRenderer(siteUrl: String): LinkRenderer = {
    new LinkRenderer() {
      override def render(node: ExpLinkNode, text: String): Rendering = {
        if (!node.url.contains(siteUrl)) {
          super.render(node, text).withAttribute("target", "_blank")
        } else super.render(node, text)
      }
    }
  }

  private def createFreemarkerConfig(templateDirName: String): Configuration = {
    val cfg = new Configuration(Configuration.VERSION_2_3_20)
    cfg.setDirectoryForTemplateLoading(new File(templateDirName))
    cfg.setDefaultEncoding("UTF-8")
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER)
    cfg
  }

  private def generateSitemap(siteCommonData: Map[String, String], postData: Seq[Map[String, String]], currentOutputDir: Path,
                              sitemapTemplate: Template, additionalMap: Map[String, String]): Unit = {
    logger.info("Generating the sitemap")
    val publishedPosts = postData.filter { post =>
      val postStatus = post.get("status")
      postStatus.contains("published")
    }
    val posts = seqAsJavaList(publishedPosts.map { post => mapAsJavaMap(post) }.sortWith(_.get("date") > _.get("date")))
    val inputProps = mapAsJavaMap { additionalMap ++ Map(
      "posts" -> posts,
      "site" -> mapAsJavaMap(siteCommonData)
    ) }
    if (Files.notExists(currentOutputDir)) {
      Files.createDirectories(currentOutputDir)
    }
    val sitemapOutputFile = new File(currentOutputDir.toFile, SiteMapFilename)
    renderTemplate(sitemapOutputFile, sitemapTemplate, inputProps)
    logger.info("The sitemap was generated")
  }

  private def generateIndexPage(siteCommonData: Map[String, String], indexOutputDir: Path, indexTemplate: Template): Unit = {
    logger.info("Generating the index page")
    val indexOutputFile = new File(indexOutputDir.toFile, IndexFilename)
    renderTemplate(indexOutputFile, indexTemplate, mapAsJavaMap(Map(
      "site" -> mapAsJavaMap(siteCommonData)
    )))
    logger.info("The index page was generated")
  }

  private def generateArchivePage(siteCommonData: Map[String, String], postData: Seq[Map[String, String]], archiveOutput: Path, archiveTemplate: Template): Unit = {
    logger.info("Generating the archive page")
    val publishedPosts = postData.filter { post =>
      val postStatus = post.get("status")
      postStatus.contains("published")
    }
    val posts = seqAsJavaList(publishedPosts.map { post => mapAsJavaMap(post) }.sortWith(_.get("date") > _.get("date")))
    val archiveInput = mapAsJavaMap { Map(
      "posts" -> posts,
      "site" -> mapAsJavaMap(siteCommonData)
    ) }
    if (Files.notExists(archiveOutput)) {
      Files.createDirectories(archiveOutput)
    }
    val archiveOutputFile = new File(archiveOutput.toFile, "index.html")
    renderTemplate(archiveOutputFile, archiveTemplate, archiveInput)
    logger.info("The archive page was generated")
  }

  private def processMdFile(mdFile: File,
        mdGenerator: PegDownProcessor, linkRenderer: LinkRenderer): Map[String, String] = {
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
    val renderedMdContent = mdGenerator.markdownToHtml(mdContent, linkRenderer)
    val simpleFilename = Paths.get(mdFile.getParentFile.getName, mdFile.getName).toString

    val contentObj = contentPropertyMap ++ Map(
      "body" -> renderedMdContent,
      "sourceDirectoryPath" -> mdFile.getParentFile.getAbsolutePath,
      "sourceFilename" -> simpleFilename,
      "uri" -> contentPropertyMap("link")
    )
    contentObj
  }

  private def generateSingleBlogFile(siteCommonData: Map[String, String], contentObj: Map[String, String],
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

      val input = mapAsJavaMap { Map(
        "content" -> mapAsJavaMap(contentObj),
        "site" -> mapAsJavaMap(siteCommonData)
      ) }
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
