package com.appliedscala.generator

import java.io.{File, FileWriter, PrintWriter}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import akka.actor.ActorSystem
import org.apache.commons.cli.{DefaultParser, HelpFormatter, Options}
import org.apache.commons.io.FileUtils
import org.pegdown.LinkRenderer.Rendering
import org.pegdown.ast.ExpLinkNode
import org.slf4j.LoggerFactory
import freemarker.template.{Configuration, Template, TemplateExceptionHandler}
import org.pegdown._

import scala.io.Source
import scala.util.Try
import monix.eval.Task
import com.beachape.filemanagement.RxMonitor
import java.nio.file.StandardWatchEventKinds._

import monix.execution.CancelableFuture

import scala.collection.JavaConversions._

object GenerationMode extends Enumeration {
  type GenerationMode = Value
  val Once = Value("Once")
  val Monitor = Value("Monitor")
  val MonitorNoServer = Value("MonitorNoServer")
}

case class CustomTemplateGeneration(name: String, template: Template)

case class Directories(basedir: String, content: String, output: String, archive: String, templates: String)
case class Templates(post: String, archive: String, sitemap: String, index: String, custom: Seq[String])
case class Site(title: String, description: String, host: String, lastmod: String)
case class HttpServer(port: Int)
case class S2GenConf(directories: Directories, templates: Templates, site: Site, server: HttpServer)

case class MarkdownProcessor(pegDownProcessor: PegDownProcessor, linkRenderer: LinkRenderer)
case class HtmlTemplates(postTemplate: Template, archiveTemplate: Template,
                         sitemapTemplate: Template, indexTemplate: Template,
                         customTemplates: Seq[CustomTemplateGeneration])
case class OutputPaths(archiveOutput: Path, sitemapOutputDir: Path, indexOutputDir: Path,
                       siteDir: Path)

object SiteGenerator {

  import GenerationMode._

  val logger = LoggerFactory.getLogger("S2Generator")
  val PropertiesSeparator = "~~~~~~"
  val DefaultConfFile = "s2gen.json"
  val SiteMapFilename = "sitemap.xml"
  val IndexFilename = "index.html"

  val OptionVersion = "version"
  val InitOption = "init"
  val HelpOption = "help"
  val OnceOption = "once"
  val NoServerOption = "noserver"

  def main(args: Array[String]): Unit = {

    val generationMode = parseCommandLineArgs(args)
    val s2conf = parseConfigOrExit(DefaultConfFile)

    val contentDirFile = Paths.get(s2conf.directories.basedir, s2conf.directories.content)
    val mdProcessor = createMarkdownProcessor(s2conf.site.host)
    val templatesDirName = Paths.get(s2conf.directories.basedir, s2conf.directories.templates).toString
    val outputPaths = getOutputPaths(s2conf)
    val mdContentFiles = recursiveListFiles(contentDirFile.toFile).filterNot(_.isDirectory)
    val siteCommonData = Map("title" -> s2conf.site.title, "description" -> s2conf.site.description)

    def regenerate(): CancelableFuture[Seq[Unit]] = {
      // Making Freemarker re-read templates on every change
      val htmlTemplatesJob = Task.delay { createHtmlTemplates(templatesDirName, s2conf) }

      val cleaningJob = Task.delay {
        logger.info("Cleaning previous version of the site")
        FileUtils.deleteDirectory(outputPaths.archiveOutput.toFile)
        Files.deleteIfExists(Paths.get(outputPaths.sitemapOutputDir.toString, SiteMapFilename))
        Files.deleteIfExists(Paths.get(outputPaths.indexOutputDir.toString, IndexFilename))
      }

      val mdProcessingJob = Task.delay {
        logger.info("Generation started")
        val postData = mdContentFiles.map { mdFile =>
          processMdFile(mdFile, mdProcessor)
        }
        postData
      }

      val resultT = for {
        htmlTemplates <- htmlTemplatesJob
        _ <- cleaningJob
        postData <- mdProcessingJob
        archiveJob = generateArchivePage(siteCommonData, postData, outputPaths.archiveOutput,
          htmlTemplates.archiveTemplate)
        sitemapJob = generateSitemap(siteCommonData, postData,
          outputPaths.sitemapOutputDir, htmlTemplates.sitemapTemplate,
          Map("siteHost" -> s2conf.site.host, "lastmod" -> s2conf.site.lastmod))
        indexJob = generateIndexPage(siteCommonData, outputPaths.indexOutputDir,
          htmlTemplates.indexTemplate)
        customPageJobs = generateCustomPages(siteCommonData, outputPaths.indexOutputDir,
          htmlTemplates.customTemplates)
        postJobs = postData.map { contentObj =>
          generateSingleBlogFile(siteCommonData, contentObj, outputPaths.siteDir.toString,
            htmlTemplates.postTemplate)
        }
        result <- Task.sequence(Seq(archiveJob, sitemapJob, indexJob) ++ customPageJobs ++ postJobs)
      } yield result

      import monix.execution.Scheduler.Implicits.global
      resultT.runAsync
    }

    val cf = regenerate()
    logFutureResult(cf)

    if (generationMode != GenerationMode.Once) {
      val httpServer = StaticServer(outputPaths.siteDir.toString, s2conf.server.port,
        generationMode == GenerationMode.MonitorNoServer)
      httpServer.start()

      logger.info("Registering a file watcher")
      implicit val actorSystem = ActorSystem.create("actor-world")

      val monitor = RxMonitor()
      val observable = monitor.observable
      val subscription = observable.subscribe(
        onNext = { pathEvent =>
          logger.info(s"A markdown file has been changed, regenerating the HTML")
          logFutureResult(regenerate())
        },
        onError = { exc => logger.error("Exception occurred", exc) },
        onCompleted = { () => logger.info("Monitor has been shut down") }
      )

      Files.walkFileTree(contentDirFile, new SimpleFileVisitor[Path]() {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          monitor.registerPath(ENTRY_MODIFY, dir)
          FileVisitResult.CONTINUE
        }
      })
      monitor.registerPath(ENTRY_MODIFY, Paths.get(templatesDirName))

      logger.info(s"Waiting for changes...")
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = {
          logger.info("Stopping the monitor")
          monitor.stop()
          httpServer.stop()
        }
      })
    } else {
      // Waiting only in the "once" mode. In the "monitor" mode, actor system will prevent us from exiting
      import scala.concurrent.Await
      import scala.concurrent.duration._

      Await.result(cf, 5.seconds)
    }
  }

  private def logFutureResult(cf: CancelableFuture[Seq[Unit]]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    cf.onSuccess { case _ =>
      logger.info("Generation finished")
    }
    cf.onFailure { case th =>
      logger.error(s"Exception occurred while running tasks", th)
    }
  }

  private def initProjectStructure(): Unit = {
    println("Initializing...")
    val classLoader = this.getClass.getClassLoader
    copyFromClasspath(classLoader, "init/site/css/styles.css", "site/css", "styles.css")
    copyFromClasspath(classLoader, "init/s2gen.json", ".", "s2gen.json")
    copyFromClasspath(classLoader, "init/content/hello-world.md", "content/blog/2016", "hello-world.md")
    val templateNames = Seq("archive.ftl", "blog.ftl", "footer.ftl" , "header.ftl", "index.ftl",
      "main.ftl", "menu.ftl", "page.ftl", "post.ftl", "sitemap.ftl", "about.ftl", "info.ftl")
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
                              sitemapTemplate: Template, additionalMap: Map[String, String]): Task[Unit] = {
    val task = Task.delay {
      val publishedPosts = postData.filter { post =>
        val postStatus = post.get("status")
        postStatus.contains("published")
      }
      val posts = seqAsJavaList(publishedPosts.map { post => mapAsJavaMap(post) }.sortWith(_.get("date") > _.get("date")))
      val inputProps = mapAsJavaMap {
        additionalMap ++ Map(
          "posts" -> posts,
          "site" -> mapAsJavaMap(siteCommonData)
        )
      }
      if (Files.notExists(currentOutputDir)) {
        Files.createDirectories(currentOutputDir)
      }
      val sitemapOutputFile = new File(currentOutputDir.toFile, SiteMapFilename)
      renderTemplate(sitemapOutputFile, sitemapTemplate, inputProps)
      logger.info(s"Successfully generated: <sitemap>")
    }
    task
  }

  private def generateIndexPage(siteCommonData: Map[String, String], indexOutputDir: Path, indexTemplate: Template): Task[Unit] = {
    val task = Task.delay {
      val indexOutputFile = new File(indexOutputDir.toFile, IndexFilename)
      renderTemplate(indexOutputFile, indexTemplate, mapAsJavaMap(Map(
        "site" -> mapAsJavaMap(siteCommonData)
      )))
      logger.info(s"Successfully generated: <index>")
    }
    task
  }

  private def generateCustomPages(siteCommonData: Map[String, String], indexOutputDir: Path, customTemplateGens: Seq[CustomTemplateGeneration]): Seq[Task[Unit]] = {
    customTemplateGens.map { gen =>
      val task = Task.delay {
        val dirName = Paths.get(indexOutputDir.toString, gen.name)
        if (Files.notExists(dirName)) {
          Files.createDirectories(dirName)
        }
        val indexOutputFile = new File(dirName.toString, IndexFilename)
        renderTemplate(indexOutputFile, gen.template, mapAsJavaMap(Map(
          "site" -> mapAsJavaMap(siteCommonData)
        )))
        logger.info(s"Successfully generated: <${gen.name}>")
      }
      task
    }
  }

  private def generateArchivePage(siteCommonData: Map[String, String], postData: Seq[Map[String, String]],
            archiveOutput: Path, archiveTemplate: Template): Task[Unit] = {
    val task = Task.delay {
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
      logger.info(s"Successfully generated: <archive>")
    }
    task
  }

  val PreviewSplitter = """\[\/\/\]\: \# \"__PREVIEW__\""""

  private def extractPreview(contentMd: String): Option[String] = {
    val contentLength = contentMd.length
    val previewParts = contentMd.split(PreviewSplitter)
    if (previewParts.length > 1 && previewParts(1).trim.length > 0) {
      Some(previewParts(1))
    } else if (previewParts.nonEmpty && previewParts(0).trim.length > 0 && previewParts(0).length < contentLength) {
      Some(previewParts(0))
    } else {
      None
    }
  }

  private def processMdFile(mdFile: File,
                            mdProcessor: MarkdownProcessor): Map[String, String] = {
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
    val mdPreview = extractPreview(mdContent)
    val renderedMdContent = mdProcessor.pegDownProcessor.markdownToHtml(mdContent, mdProcessor.linkRenderer)
    val htmlPreview = mdPreview.map { preview => mdProcessor.pegDownProcessor.markdownToHtml(preview, mdProcessor.linkRenderer) }
    val simpleFilename = Paths.get(mdFile.getParentFile.getName, mdFile.getName).toString

    val mapBuilder = Map.newBuilder[String, String]
    mapBuilder ++= contentPropertyMap
    mapBuilder ++= Map(
      "body" -> renderedMdContent,
      "sourceDirectoryPath" -> mdFile.getParentFile.getAbsolutePath,
      "sourceFilename" -> simpleFilename,
      "uri" -> contentPropertyMap("link")
    )
    htmlPreview.foreach { preview =>
      mapBuilder += "preview" -> preview
    }
    mapBuilder.result()
  }

  private def generateSingleBlogFile(siteCommonData: Map[String, String], contentObj: Map[String, String],
                                globalOutputDir: String, template: Template): Task[Unit] = {
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
      logger.info(s"Successfully generated: $sourceFilename")
    }
    task
  }

  private def renderTemplate(outputFile: File, template: Template, input: java.util.Map[String, _]): Unit = {
    val fileWriter = new FileWriter(outputFile)
    try {
      template.process(input, fileWriter)
    } finally {
      fileWriter.close()
    }
  }

  private def runTasks(tasks: Seq[Task[Unit]]): CancelableFuture[Seq[Unit]] = {
    import monix.execution.Scheduler.Implicits.global

    val resultT = Task.sequence(tasks)
    resultT.runAsync
  }

  private def getOutputPaths(s2conf: S2GenConf): OutputPaths = {
    val siteDirPath = Paths.get(s2conf.directories.basedir, s2conf.directories.output)
    val siteDir = siteDirPath.toString
    val archiveOutput = Paths.get(siteDir, s2conf.directories.archive)
    val sitemapOutputDir = Paths.get(siteDir)
    val indexOutputDir = Paths.get(siteDir)
    OutputPaths(archiveOutput, sitemapOutputDir, indexOutputDir, siteDirPath)
  }

  private def createHtmlTemplates(templatesDirName: String, s2conf: S2GenConf): HtmlTemplates = {
    val cfg = createFreemarkerConfig(templatesDirName)
    val postTemplate = cfg.getTemplate(s2conf.templates.post)
    val archiveTemplate = cfg.getTemplate(s2conf.templates.archive)
    val sitemapTemplate = cfg.getTemplate(s2conf.templates.sitemap)
    val indexTemplate = cfg.getTemplate(s2conf.templates.index)
    val customTemplates = s2conf.templates.custom.map { name =>
      CustomTemplateGeneration(name.replaceAll("\\.ftl$", ""), cfg.getTemplate(name))
    }
    HtmlTemplates(postTemplate, archiveTemplate, sitemapTemplate, indexTemplate, customTemplates)
  }

  private def createMarkdownProcessor(host: String): MarkdownProcessor = {
    val pgPluginsCode = Extensions.TABLES | Extensions.FENCED_CODE_BLOCKS
    val mdGenerator = new PegDownProcessor(pgPluginsCode)
    val linkRenderer = createLinkRenderer(host)
    MarkdownProcessor(mdGenerator, linkRenderer)
  }

  private def parseConfigOrExit(confFileName: String): S2GenConf = {

    if (!Files.exists(Paths.get(DefaultConfFile))) {
      System.err.println(s"Cannot find a configuration file $DefaultConfFile")
      System.exit(-1)
    }

    import io.circe.jawn._
    import io.circe.generic.auto._
    import scala.io.Source

    val confStr = Source.fromFile(DefaultConfFile).getLines().mkString("")
    val s2confE = decode[S2GenConf](confStr)

    s2confE.recover { case error =>
      logger.error("Error occurred while parsing the configuration file: ", error)
      System.exit(-1)
    }

    s2confE.toOption.get
  }

  private def parseCommandLineArgs(args: Array[String]): GenerationMode = {
    val options = new Options
    options.addOption(OptionVersion, false, "print the version information")
    options.addOption(InitOption, false, "initialize project structure and exit")
    options.addOption(OnceOption, false, "generate the site once and exits without starting the monitoring")
    options.addOption(HelpOption, false, "print this message")
    options.addOption(NoServerOption, false, "start monitoring without the embedded server")
    val helpFormatter = new HelpFormatter
    val parser = new DefaultParser
    val cmd = parser.parse(options, args)

    if (cmd.hasOption(OptionVersion)) {
      val versionNumberT = Try { CustomTemplateGeneration.getClass.getPackage.getImplementationVersion }
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

    if (cmd.hasOption(OnceOption)) {
      GenerationMode.Once
    } else {
      if (cmd.hasOption(NoServerOption)) {
        GenerationMode.MonitorNoServer
      } else {
        GenerationMode.Monitor
      }
    }
  }
}
