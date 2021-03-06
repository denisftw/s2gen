package com.appliedscala.generator.services

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors.{ApplicationError, GenerationError, SystemError, UserError}
import com.appliedscala.generator.model._
import monix.eval.Task
import monix.execution.CancelableFuture
import org.apache.commons.io.FileUtils
import org.htmlcleaner.HtmlCleaner
import org.slf4j.LoggerFactory
import zio.{ExitCode, IO, Task => ZTask}

import java.io._
import java.nio.file._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class GenerationService(commandLineService: CommandLineService, httpServerService: HttpServerService,
    configurationReadingService: ConfigurationReadingService, markdownService: MarkdownService,
    translationService: TranslationService, pageGenerationService: PageGenerationService,
    templateService: TemplateService) {

  import java.util.{Date => JavaDate}

  private val logger = LoggerFactory.getLogger(this.getClass)

  def runZ(args: Array[String]): ZTask[Unit] = {
    commandLineService
      .parseCommandLineArgsZ(args)
      .flatMap(extractGenerationMode)
      .foldM(handleGenerationError, handleExitCode)
  }

  private def handleExitCode(exitCode: ExitCode): ZTask[Unit] = ZTask.succeed {
    System.exit(exitCode.code)
  }

  private def handleGenerationError(error: ApplicationError): ZTask[Unit] = ZTask.succeed {
    error match {
      case userError: UserError     => println(userError.message)
      case systemError: SystemError => logger.error("Exception occurred", systemError.cause)
    }
    System.exit(ExitCode.failure.code)
  }

  private def extractGenerationMode(parsed: Either[Unit, GenerationMode]): IO[GenerationError, ExitCode] = {
    parsed match {
      case Left(_)     => IO.succeed(ExitCode.success)
      case Right(mode) => generate(mode)
    }
  }

  private def generate(generationMode: GenerationMode): IO[GenerationError, ExitCode] = IO.succeed {
    configurationReadingService.readConfiguration().flatMap { conf =>
      val contentDirFile = Paths.get(conf.directories.basedir, conf.directories.content)
      val mdProcessor = markdownService.createMarkdownProcessor(conf.site.host)
      val htmlCleaner = new HtmlCleaner()
      val templatesDirName = Paths.get(conf.directories.basedir, conf.directories.templates).toString
      val i18nDirName = Paths.get(conf.directories.basedir, conf.directories.templates, "i18n")
      val outputPaths = getOutputPaths(conf)
      val settingsCommonData = Map("title" -> conf.site.title, "description" -> conf.site.description,
        "siteHost" -> conf.site.host, "lastmod" -> conf.site.lastmod)

      def regenerate(): ZTask[Unit] = {
        // Rereading content files on every change in case some of them are added/deleted
        val mdContentFilesZ = ZTask { recursiveListFiles(contentDirFile.toFile).filterNot(_.isDirectory) }

        val translationsZ = translationService.buildTranslationsZ(i18nDirName, outputPaths.siteDir)

        // Making Freemarker re-read templates on every change
        val htmlTemplatesJobZ = ZTask { templateService.createTemplates(templatesDirName, conf) }
        // Last build date changes on every rebuild
        val siteCommonData: Map[String, Object] = settingsCommonData + ("lastBuildDateJ" -> new JavaDate())

        val cleaningJobZ = ZTask {
          logger.info("Cleaning previous version of the site")
          FileUtils.deleteDirectory(new File(outputPaths.archiveOutput))
          Files.deleteIfExists(Paths.get(outputPaths.indexOutputDir.toString, pageGenerationService.IndexFilename))
        }

        val mdProcessingJobZ = mdContentFilesZ.map { mdContentFiles =>
          logger.info("Generation started")
          val postData = mdContentFiles.map { mdFile =>
            markdownService.processMdFile(mdFile, htmlCleaner, mdProcessor)
          }
          postData
        }

        val resultT = for {
          htmlTemplates <- htmlTemplatesJobZ
          _ <- cleaningJobZ
          postData <- mdProcessingJobZ
          translations <- translationsZ
          archiveJob = pageGenerationService.generateArchivePage(siteCommonData, postData, outputPaths.archiveOutput,
            htmlTemplates.archiveTemplate, translations)
          indexJob = pageGenerationService.generateIndexPage(siteCommonData, outputPaths.indexOutputDir,
            htmlTemplates.indexTemplate, translations)
          customPageJobs = pageGenerationService.generateCustomPages(siteCommonData, postData,
            outputPaths.indexOutputDir, htmlTemplates.customHtmlTemplates, htmlTemplates.customXmlTemplates,
            translations)
          postJobs = pageGenerationService.generatePostPages(postData, siteCommonData, outputPaths, htmlTemplates,
            translations)
          allJobs = Seq(archiveJob, indexJob) ++ customPageJobs ++ postJobs
//          result <- ZTask.collectAll(allJobs)
        } yield ()

//        resultT.runToFuture
        ???
      }
      ???
    }
    ???
  }

  def run(args: Array[String]): Unit = {
    val generationMode = commandLineService.parseCommandLineArgs(args) match {
      case Left(_)      => System.exit(0)
      case Right(value) => value
    }
    val conf = configurationReadingService.parseConfigOrExit()

    val contentDirFile = Paths.get(conf.directories.basedir, conf.directories.content)
    val mdProcessor = markdownService.createMarkdownProcessor(conf.site.host)
    val htmlCleaner = new HtmlCleaner()
    val templatesDirName = Paths.get(conf.directories.basedir, conf.directories.templates).toString
    val i18nDirName = Paths.get(conf.directories.basedir, conf.directories.templates, "i18n")
    val outputPaths = getOutputPaths(conf)
    val settingsCommonData = Map("title" -> conf.site.title, "description" -> conf.site.description,
      "siteHost" -> conf.site.host, "lastmod" -> conf.site.lastmod)

    def regenerate(): CancelableFuture[Seq[Unit]] = {
      // Rereading content files on every change in case some of them are added/deleted
      val mdContentFiles = recursiveListFiles(contentDirFile.toFile).filterNot(_.isDirectory)

      val translations = translationService.buildTranslations(i18nDirName, outputPaths.siteDir)

      // Making Freemarker re-read templates on every change
      val htmlTemplatesJob = Task.delay { templateService.createTemplates(templatesDirName, conf) }
      // Last build date changes on every rebuild
      val siteCommonData: Map[String, Object] = settingsCommonData + ("lastBuildDateJ" -> new JavaDate())

      val cleaningJob = Task.delay {
        logger.info("Cleaning previous version of the site")
        FileUtils.deleteDirectory(new File(outputPaths.archiveOutput))
        Files.deleteIfExists(Paths.get(outputPaths.indexOutputDir.toString, pageGenerationService.IndexFilename))
      }

      val mdProcessingJob = Task.delay {
        logger.info("Generation started")
        val postData = mdContentFiles.map { mdFile =>
          markdownService.processMdFile(mdFile, htmlCleaner, mdProcessor)
        }
        postData
      }

      val resultT = for {
        htmlTemplates <- htmlTemplatesJob
        _ <- cleaningJob
        postData <- mdProcessingJob
        archiveJob = pageGenerationService.generateArchivePage(siteCommonData, postData, outputPaths.archiveOutput,
          htmlTemplates.archiveTemplate, translations)
        indexJob = pageGenerationService.generateIndexPage(siteCommonData, outputPaths.indexOutputDir,
          htmlTemplates.indexTemplate, translations)
        customPageJobs = pageGenerationService.generateCustomPages(siteCommonData, postData, outputPaths.indexOutputDir,
          htmlTemplates.customHtmlTemplates, htmlTemplates.customXmlTemplates, translations)
        postJobs = pageGenerationService.generatePostPages(postData, siteCommonData, outputPaths, htmlTemplates,
          translations)
        result <- Task.sequence(Seq(archiveJob, indexJob) ++ customPageJobs ++ postJobs)
      } yield result

      import monix.execution.Scheduler.Implicits.global
      resultT.runToFuture
    }

    val cf = regenerate()
    logFutureResult(cf)

    if (generationMode != GenerationMode.Once) {
      val maybeServer = if (generationMode != GenerationMode.MonitorNoServer) {
        httpServerService.start(outputPaths.siteDir.toString, conf.server.port) match {
          case Left(th) =>
            logger.warn(s"Cannot start HTTP server on port ${conf.server.port}", th)
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

  private def logFutureResult(cf: CancelableFuture[Seq[Unit]]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    cf.andThen {
      case Success(_)  => logger.info("Generation finished")
      case Failure(th) => logger.error(s"Exception occurred while running tasks", th)
    }
  }

  private def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }

  private def getOutputPaths(conf: ApplicationConfiguration): OutputPaths = {
    val siteDirPath = Paths.get(conf.directories.basedir, conf.directories.output)
    val siteDir = siteDirPath.toString
    val archiveOutput = conf.directories.archive
    val indexOutputDir = Paths.get(siteDir)
    OutputPaths(archiveOutput, indexOutputDir, siteDirPath)
  }
}
