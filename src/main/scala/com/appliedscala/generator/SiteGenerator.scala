package com.appliedscala.generator

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.configuration.S2GenConf
import com.appliedscala.generator.errors.ConfigurationParsingException
import com.appliedscala.generator.extensions.TargetBlankLinkRendererExtension
import com.appliedscala.generator.model._
import com.appliedscala.generator.services.{CommandLineService, HttpServerService, InitService}
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.{Parser, PegdownExtensions}
import com.vladsch.flexmark.profile.pegdown.PegdownOptionsAdapter

import java.io._
import java.nio.charset.Charset
import java.nio.file._
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import freemarker.template.{Configuration, Template, TemplateExceptionHandler}

import scala.io.Source
import scala.util.{Failure, Success, Using}
import monix.eval.Task

import java.text.SimpleDateFormat
import java.util.Properties
import monix.execution.CancelableFuture
import org.htmlcleaner.HtmlCleaner
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

object SiteGenerator {

  import java.util.{Date => JavaDate}
  import java.util.{Map => JavaMap}

  private val logger = LoggerFactory.getLogger("S2Generator")
  private val DateFormatter = new SimpleDateFormat("yyyy-MM-dd")
  private val PropertiesSeparator = "~~~~~~"
  private val DefaultConfFile = "s2gen.json"
  private val IndexFilename = "index.html"

  def main(args: Array[String]): Unit = {

    val initService = new InitService
    val commandLineService = new CommandLineService(initService)
    val generationMode = commandLineService.parseCommandLineArgs(args) match {
      case Left(_)      => System.exit(0)
      case Right(value) => value
    }
    val httpServerService = new HttpServerService
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
        indexJob = generateIndexPage(siteCommonData, outputPaths.indexOutputDir, htmlTemplates.indexTemplate,
          translations)
        customPageJobs = generateCustomPages(siteCommonData, postData, outputPaths.indexOutputDir,
          htmlTemplates.customHtmlTemplates, htmlTemplates.customXmlTemplates, translations)
        postJobs = generatePostPages(postData, siteCommonData, outputPaths, htmlTemplates, translations)
        result <- Task.sequence(Seq(archiveJob, indexJob) ++ customPageJobs ++ postJobs)
      } yield result

      import monix.execution.Scheduler.Implicits.global
      resultT.runToFuture
    }

    val cf = regenerate()
    logFutureResult(cf)

    if (generationMode != GenerationMode.Once) {
      val maybeServer = if (generationMode != GenerationMode.MonitorNoServer) {
        httpServerService.start(outputPaths.siteDir.toString, s2conf.server.port) match {
          case Left(th) =>
            logger.warn(s"Cannot start HTTP server on port ${s2conf.server.port}", th)
            None
          case Right(value) => Some(value)
        }
      } else None

      logger.info("Registering a file watcher")
      def fileChanged(file: files.File, action: String): Unit = {
        logger.info(s"File '${file.path.getFileName}' has been $action, regenerating")
        logFutureResult(regenerate())
      }
      val monitor = new FileMonitor(contentDirFile, recursive = true) {
        override def onCreate(file: files.File, count: Int): Unit = fileChanged(file, "created")
        override def onModify(file: files.File, count: Int): Unit = fileChanged(file, "updated")
        override def onDelete(file: files.File, count: Int): Unit = fileChanged(file, "deleted")
        override def onException(exc: Throwable): Unit = logger.error("Exception occurred", exc)
      }

      monitor.start()(ExecutionContext.global)
      logger.info(s"Waiting for changes...")
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = {
          logger.info("Stopping the monitor")
          httpServerService.stop(maybeServer)
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
    import scala.jdk.CollectionConverters.PropertiesHasAsScala
    val i18nListBuffer = new ListBuffer[TranslationBundle]
    if (Files.isDirectory(i18nDirName)) {
      i18nDirName.toFile.listFiles().map { propertyFile =>
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
    if (result.isEmpty) Seq(TranslationBundle("", Map.empty[String, Object], new File(siteDir.toFile, "").toPath))
    else result
  }

  private def logFutureResult(cf: CancelableFuture[Seq[Unit]]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    cf.andThen {
      case Success(_)  => logger.info("Generation finished")
      case Failure(th) => logger.error(s"Exception occurred while running tasks", th)
    }
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

  private def generatePostPages(postData: Seq[Map[String, String]], siteCommonData: Map[String, Object],
      outputPaths: OutputPaths, htmlTemplates: HtmlTemplates, translations: Seq[TranslationBundle]): Seq[Task[Unit]] = {
    translations.flatMap { langBundle =>
      val langOutputDir = langBundle.siteDir.toFile
      if (!langOutputDir.exists()) {
        langOutputDir.mkdir()
      }
      postData.filter { obj =>
        obj.get("type").contains("post")
      } map { contentObj =>
        generateSingleBlogFile(siteCommonData, contentObj, langOutputDir.toString, htmlTemplates.postTemplate,
          langBundle)
      }
    }
  }

  private def generateIndexPage(siteCommonData: Map[String, Object], indexOutputDir: Path, indexTemplate: Template,
      translations: Seq[TranslationBundle]): Task[Unit] = {
    val task = Task.delay {
      translations.foreach { langBundle =>
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
        )
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
      translationBundle: TranslationBundle): Task[JavaMap[String, Object]] =
    Task.delay {
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

  private def generateCustomPages(siteCommonData: Map[String, Object], postData: Seq[Map[String, String]],
      indexOutputDir: Path, customTemplateGens: Seq[CustomHtmlTemplateDescription],
      customXmlTemplateDescriptions: Seq[CustomXmlTemplateDescription], translations: Seq[TranslationBundle])
      : Seq[Task[Unit]] = {

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

  private val PreviewSplitter = """\[\/\/\]\: \# \"__PREVIEW__\""""

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

  private def processMdFile(
      mdFile: File, htmlCleaner: HtmlCleaner, mdProcessor: MarkdownProcessor): Map[String, String] = {
    val postContent = Using.resource(Source.fromFile(mdFile))(_.getLines().toList)
    val separatorLineNumber = postContent.indexWhere(_.startsWith(PropertiesSeparator))
    val propertiesLines = postContent.take(separatorLineNumber)
    val contentLines = postContent.drop(separatorLineNumber + 1)

    val contentPropertyMap = propertiesLines.flatMap { propertyLine =>
      val pair = propertyLine.split("=")
      pair match {
        case Array(first, second) => Some(first -> second)
        case _                    => None
      }
    }.toMap
    val mdContent = contentLines.mkString("\n")
    val mdPreview = extractPreview(mdContent)

    val renderedMdContent = mdProcessor.renderer.render(mdProcessor.parser.parse(mdContent))
    val htmlPreview = mdPreview.map { preview =>
      mdProcessor.renderer.render(mdProcessor.parser.parse(preview))
    }
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
      val outputLink = contentObj.getOrElse("link",
        throw new Exception(s"The required link property is not specified for $sourceFilename"))

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
    val linkRendererExtension = new TargetBlankLinkRendererExtension(host)
    val pegdownExtensions = PegdownExtensions.TABLES | PegdownExtensions.FENCED_CODE_BLOCKS
    val options = PegdownOptionsAdapter.flexmarkOptions(pegdownExtensions, linkRendererExtension)
    val parser = Parser.builder(options).build()
    val renderer = HtmlRenderer.builder(options).build()
    MarkdownProcessor(parser, renderer)
  }

  private def parseConfigOrExit(confFileName: String): S2GenConf = {

    if (!Files.exists(Paths.get(confFileName))) {
      System.err.println(s"Cannot find a configuration file $confFileName")
      System.exit(-1)
    }

    val confStr = Using.resource(Source.fromFile(confFileName))(_.getLines().mkString(""))
    Json.parse(confStr).validate[S2GenConf] match {
      case JsSuccess(value, _) => value
      case error: JsError =>
        val errorMessage = JsError.Message.unapply(error).getOrElse("unknown error")
        val exception = new ConfigurationParsingException(errorMessage)
        logger.error("Error occurred while parsing the configuration file", exception)
        System.exit(-1)
        throw exception
    }
  }
}
