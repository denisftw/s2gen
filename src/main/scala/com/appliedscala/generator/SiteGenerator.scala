package com.appliedscala.generator

import java.io._
import java.nio.charset.Charset
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
import scala.util.{Properties, Try}
import monix.eval.Task
import com.beachape.filemanagement.RxMonitor
import java.nio.file.StandardWatchEventKinds._
import java.text.SimpleDateFormat
import java.util.Properties

import monix.execution.CancelableFuture
import org.htmlcleaner.HtmlCleaner

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

object GenerationMode extends Enumeration {
  type GenerationMode = Value
  val Once = Value("Once")
  val Monitor = Value("Monitor")
  val MonitorNoServer = Value("MonitorNoServer")
}

case class CustomHtmlTemplateDescription(name: String, template: Template)
case class CustomXmlTemplateDescription(name: String, template: Template)

case class Directories(basedir: String, content: String, output: String, archive: String, templates: String)
case class Templates(post: String, archive: String, index: String, custom: Seq[String], customXml: Seq[String])
case class TranslationBundle(langCode: String, messages: Map[String, Object], siteDir: Path)
case class Site(title: String, description: String, host: String, lastmod: String)
case class HttpServer(port: Int)
case class S2GenConf(directories: Directories, templates: Templates, site: Site, server: HttpServer)

case class MarkdownProcessor(pegDownProcessor: PegDownProcessor, linkRenderer: LinkRenderer)
case class HtmlTemplates(postTemplate: Template, archiveTemplate: Template,
                         indexTemplate: Template,
                         customHtmlTemplates: Seq[CustomHtmlTemplateDescription], customXmlTemplates: Seq[CustomXmlTemplateDescription])
case class OutputPaths(archiveOutput: String, indexOutputDir: Path, siteDir: Path)

object SiteGenerator {

  import java.util.{Date => JavaDate}
  import java.util.{Map => JavaMap}
  import GenerationMode._

  val logger = LoggerFactory.getLogger("S2Generator")
  val DateFormatter = new SimpleDateFormat("yyyy-MM-dd")
  val PropertiesSeparator = "~~~~~~"
  val DefaultConfFile = "s2gen.json"
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
    val htmlCleaner = new HtmlCleaner()
    val templatesDirName = Paths.get(s2conf.directories.basedir, s2conf.directories.templates).toString
    val i18nDirName = Paths.get(s2conf.directories.basedir, s2conf.directories.templates, "i18n")
    val outputPaths = getOutputPaths(s2conf)
    val settingsCommonData = Map("title" -> s2conf.site.title, "description" -> s2conf.site.description,
      "siteHost" -> s2conf.site.host, "lastmod" -> s2conf.site.lastmod)

    def regenerate(): CancelableFuture[Seq[Unit]] = {
      // Rereading content files on every change in case some of them are added/deleted
      val mdContentFiles = recursiveListFiles(contentDirFile.toFile).filterNot(_.isDirectory)

      val translations = buildTranslations(i18nDirName, outputPaths.siteDir)

      // Making Freemarker re-read templates on every change
      val htmlTemplatesJob = Task.delay { createTemplates(templatesDirName, s2conf) }
      // Last build date changes on every rebuild
      val siteCommonData: Map[String, Object] = settingsCommonData + ("lastBuildDateJ" -> new JavaDate())

      val cleaningJob = Task.delay {
        logger.info("Cleaning previous version of the site")
        FileUtils.deleteDirectory(new File(outputPaths.archiveOutput))
        Files.deleteIfExists(Paths.get(outputPaths.indexOutputDir.toString, IndexFilename))
      }

      val mdProcessingJob = Task.delay {
        logger.info("Generation started")
        val postData = mdContentFiles.map { mdFile =>
          processMdFile(mdFile, htmlCleaner, mdProcessor)
        }
        postData
      }

      val resultT = for {
        htmlTemplates <- htmlTemplatesJob
        _ <- cleaningJob
        postData <- mdProcessingJob
        archiveJob = generateArchivePage(siteCommonData, postData, outputPaths.archiveOutput,
          htmlTemplates.archiveTemplate, translations)
        indexJob = generateIndexPage(siteCommonData, outputPaths.indexOutputDir,
          htmlTemplates.indexTemplate, translations)
        customPageJobs = generateCustomPages(siteCommonData, postData, outputPaths.indexOutputDir,
          htmlTemplates.customHtmlTemplates, htmlTemplates.customXmlTemplates, translations)
        postJobs = generatePostPages(postData, siteCommonData, outputPaths, htmlTemplates, translations)
        result <- Task.sequence(Seq(archiveJob, indexJob) ++ customPageJobs ++ postJobs)
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
          logger.info(s"File '${pathEvent.path.getFileName}' has been changed, regenerating")
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

  private def buildTranslations(i18nDirName: Path, siteDir: Path): Seq[TranslationBundle] = {
    val i18nListBuffer = new ListBuffer[TranslationBundle]
    if (Files.isDirectory(i18nDirName)) {
      i18nDirName.toFile.listFiles().map { propertyFile =>
        import scala.collection.JavaConverters._
        val langCode = propertyFile.getName.split("\\.")(0)
        val prop = new Properties()
        val fileStream = new FileInputStream(propertyFile)
        prop.load(new InputStreamReader(fileStream, Charset.forName("UTF-8")))
        fileStream.close()
        if (langCode == "default") {
          i18nListBuffer += TranslationBundle("", prop.asScala.toMap, new File(siteDir.toFile, "").toPath)
        } else {
          i18nListBuffer += TranslationBundle(langCode, prop.asScala.toMap, new File(siteDir.toFile, langCode).toPath)
        }
      }
    }
    val result = i18nListBuffer.result()
    if (result.isEmpty) Seq(TranslationBundle("", Map.empty[String, Object], new File(siteDir.toFile, "").toPath)) else result
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
      "main.ftl", "menu.ftl", "page.ftl", "post.ftl", "sitemap.ftl", "about.ftl", "info.ftl", "feed.ftl")
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
        if (!node.url.startsWith("/") && !node.url.contains(siteUrl)) {
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

  private def generatePostPages(postData: Seq[Map[String, String]], siteCommonData: Map[String, Object], outputPaths: OutputPaths,
                                htmlTemplates: HtmlTemplates, translations: Seq[TranslationBundle]): Seq[Task[Unit]] = {
    translations.flatMap { langBundle =>
      val langOutputDir = langBundle.siteDir.toFile
      if (!langOutputDir.exists()) {
        langOutputDir.mkdir()
      }
      postData.filter { obj =>
        obj.get("type").contains("post")
      } map { contentObj =>
        generateSingleBlogFile(siteCommonData, contentObj, langOutputDir.toString,
          htmlTemplates.postTemplate, langBundle)
      }
    }
  }

  private def generateIndexPage(siteCommonData: Map[String, Object], indexOutputDir: Path,
                  indexTemplate: Template, translations: Seq[TranslationBundle]): Task[Unit] = {
    val task = Task.delay {
      translations.foreach { langBundle =>
        val langOutputDir = langBundle.siteDir.toFile
        if (!langOutputDir.exists()) {
          langOutputDir.mkdir()
        }
        val indexOutputFile = new File(langOutputDir, IndexFilename)
        renderTemplate(indexOutputFile, indexTemplate, mapAsJavaMap(Map(
          "site" -> mapAsJavaMap(siteCommonData),
          "messages" -> mapAsJavaMap(langBundle.messages),
          "currentLanguage" -> langBundle.langCode
        )))
        logger.info(s"Successfully generated: <index> ${langBundle.langCode}")
      }
    }
    task
  }

  private def addJavaDate(post: Map[String, String]): Map[String, Object] = {
    val dateStr = post("date")
    val dateJ = DateFormatter.parse(dateStr)
    post + ("dateJ" -> dateJ)
  }

  private def buildInputProps(siteCommonData: Map[String, Object], postData: Seq[Map[String, String]],
                              translationBundle: TranslationBundle):
  Task[JavaMap[String, Object]] = Task.delay {
    val (publishedPosts, miscArticles)= postData.filter { post =>
      val postStatus = post.get("status")
      postStatus.contains("published")
    }.partition { post =>
      val postType = post.get("type")
      postType.contains("post")
    }
    val allPosts = publishedPosts.map { post =>
      mapAsJavaMap(addJavaDate(post))
    }.sortWith(_("dateJ").asInstanceOf[JavaDate].getTime > _("dateJ").asInstanceOf[JavaDate].getTime)
    val allMiscPosts = miscArticles.groupBy(_("title")).map { case (key, translations) =>
      val byTr = translations.groupBy{ obj =>
        obj.get("language").getOrElse("")
      }.map { case (key2, red) =>
        (key2, mapAsJavaMap(red.head))
      }
      (key, mapAsJavaMap(byTr))
    }
    val lastUpdated = allPosts.headOption.map(_("dateJ").asInstanceOf[JavaDate]).getOrElse(new JavaDate())
    val blogPosts = seqAsJavaList(allPosts)
    val miscPosts = mapAsJavaMap(allMiscPosts)
    val inputProps = mapAsJavaMap {
      Map(
        "posts" -> blogPosts,
        "misc" -> miscPosts,
        "site" -> mapAsJavaMap(siteCommonData ++ Map("lastPubDateJ" -> lastUpdated)),
        "messages" -> mapAsJavaMap(translationBundle.messages),
        "currentLanguage" -> translationBundle.langCode
      )
    }
    inputProps
  }

  private def generateCustomPages(siteCommonData: Map[String, Object], postData: Seq[Map[String, String]], indexOutputDir: Path,
    customTemplateGens: Seq[CustomHtmlTemplateDescription], customXmlTemplateDescriptions: Seq[CustomXmlTemplateDescription],
                                  translations: Seq[TranslationBundle]):
    Seq[Task[Unit]] = {

    val tasks = translations.map { translationBundle =>
      val taskInputProps = buildInputProps(siteCommonData, postData, translationBundle)
      val htmlPart = customTemplateGens.map { gen =>
        taskInputProps.map { inputProps =>
          val dirName = Paths.get(translationBundle.siteDir.toFile.toString, gen.name)
          if (Files.notExists(dirName)) {
            Files.createDirectories(dirName)
          }
          val indexOutputFile = new File(dirName.toString, IndexFilename)
          renderTemplate(indexOutputFile, gen.template, inputProps)
          logger.info(s"Successfully generated: <${gen.name}> ${translationBundle.langCode}")
        }
      }

      val xmlPart = customXmlTemplateDescriptions.map { gen =>
        taskInputProps.map { inputProps =>
          val dirName = Paths.get(translationBundle.siteDir.toFile.toString)
          if (Files.notExists(dirName)) {
            Files.createDirectories(dirName)
          }
          val indexOutputFile = new File(dirName.toString, gen.name)
          renderTemplate(indexOutputFile, gen.template, inputProps)
          logger.info(s"Successfully generated: <${gen.name}> ${translationBundle.langCode}")
        }
      }

      Task.sequence(htmlPart ++ xmlPart).map(_ => ())
    }
    tasks
  }

  private def generateArchivePage(siteCommonData: Map[String, Object], postData: Seq[Map[String, String]],
            archiveOutput: String, archiveTemplate: Template, translations: Seq[TranslationBundle]): Task[Unit] = {

      val tasks = translations.map { langBundle =>
        val inputPropsTask = buildInputProps(siteCommonData, postData, langBundle)
        val task = inputPropsTask.map { inputProps =>
          val langOutputDir = new File(langBundle.siteDir.toFile, archiveOutput)
          if (!langOutputDir.exists()) {
            langOutputDir.mkdirs()
          }
          val archiveOutputFile = new File(langOutputDir, "index.html")
          renderTemplate(archiveOutputFile, archiveTemplate, inputProps)
          logger.info(s"Successfully generated: <archive> ${langBundle.langCode}")
        }
        task
      }
      Task.sequence(tasks).map(_ => ())
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

  private def processMdFile(mdFile: File, htmlCleaner: HtmlCleaner,
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
      "sourceFilename" -> simpleFilename
    )
    htmlPreview.foreach { preview =>
      val previewText = htmlCleaner.clean(preview).getText.toString
      mapBuilder ++= Map(
        "preview" -> preview,
        "previewText" -> previewText
      )
    }
    mapBuilder.result()
  }

  private def generateSingleBlogFile(siteCommonData: Map[String, Object], contentObj: Map[String, String],
                                globalOutputDir: String, template: Template, langBundle: TranslationBundle): Task[Unit] = {
    val sourceFilename = contentObj("sourceFilename")
    val task = Task {
      val outputLink = contentObj.getOrElse("link", throw new Exception(
        s"The required link property is not specified for $sourceFilename"))

      val maybeLanguage = contentObj.get("language")
      if ((maybeLanguage.isDefined && maybeLanguage.contains(langBundle.langCode)) ||
          (maybeLanguage.isEmpty && langBundle.langCode.length == 0)) {
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
          "content" -> mapAsJavaMap(addJavaDate(contentObj)),
          "site" -> mapAsJavaMap(siteCommonData),
          "messages" -> mapAsJavaMap(langBundle.messages),
          "currentLanguage" -> langBundle.langCode
        ) }
        val outputFile = new File(outputDir.toFile, outputFilename)
        renderTemplate(outputFile, template, input)
        logger.info(s"Successfully generated: $sourceFilename ${langBundle.langCode}")
      }
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
    val archiveOutput = s2conf.directories.archive
    val indexOutputDir = Paths.get(siteDir)
    OutputPaths(archiveOutput, indexOutputDir, siteDirPath)
  }

  private def createTemplates(templatesDirName: String, s2conf: S2GenConf): HtmlTemplates = {
    val cfg = createFreemarkerConfig(templatesDirName)
    val postTemplate = cfg.getTemplate(s2conf.templates.post)
    val archiveTemplate = cfg.getTemplate(s2conf.templates.archive)
    val indexTemplate = cfg.getTemplate(s2conf.templates.index)
    val customTemplates = s2conf.templates.custom.map { name =>
      CustomHtmlTemplateDescription(name.replaceAll("\\.ftl$", ""), cfg.getTemplate(name))
    }
    val customXmlTemplates = s2conf.templates.customXml.map { name =>
      CustomXmlTemplateDescription(name.replaceAll("\\.ftl$", ".xml"), cfg.getTemplate(name))
    }

    HtmlTemplates(postTemplate, archiveTemplate, indexTemplate, customTemplates, customXmlTemplates)
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
      val versionNumberT = Try { CustomHtmlTemplateDescription.getClass.getPackage.getImplementationVersion }
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
