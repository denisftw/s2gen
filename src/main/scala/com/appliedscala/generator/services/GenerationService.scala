package com.appliedscala.generator.services

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors.{
  ApplicationError,
  FileMonitorStartError,
  HttpServerStartError,
  RegenerationError,
  ShutdownHookRegistrationError,
  SystemError,
  UserError
}
import com.appliedscala.generator.model._
import org.apache.commons.io.FileUtils
import org.eclipse.jetty.server.Server
import org.htmlcleaner.HtmlCleaner
import org.slf4j.LoggerFactory
import zio.{ExitCode, IO, Task, UIO, URIO, ZEnv}

import java.io._
import java.nio.file._
import scala.collection.compat.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class GenerationService(commandLineService: CommandLineService, httpServerService: HttpServerService,
    configurationReadingService: ConfigurationReadingService, markdownService: MarkdownService,
    translationService: TranslationService, pageGenerationService: PageGenerationService,
    templateService: TemplateService) {

  import java.util.{Date => JavaDate}

  private val logger = LoggerFactory.getLogger(this.getClass)

  def runZ(args: List[String]): URIO[ZEnv, ExitCode] = {
    commandLineService
      .parseCommandLineArgsZ(args.toArray)
      .flatMap(generateIfRequired)
      .fold(handleApplicationError, identity)
  }

  private def handleApplicationError(error: ApplicationError): ExitCode = {
    error match {
      case userError: UserError     => println(userError.message)
      case systemError: SystemError => logger.error("Exception occurred", systemError.cause)
    }
    ExitCode.failure
  }

  private def generateIfRequired(parsed: Either[Unit, GenerationMode]): IO[ApplicationError, ExitCode] = {
    parsed match {
      case Left(_)     => IO.succeed(ExitCode.success)
      case Right(mode) => generate(mode).map(_ => ExitCode.success)
    }
  }

  private def generate(generationMode: GenerationMode): IO[ApplicationError, Unit] = IO.effectSuspendTotal {
    configurationReadingService.readConfiguration().flatMap { conf =>
      val contentDirFile = Paths.get(conf.directories.basedir, conf.directories.content)
      val mdProcessor = markdownService.createMarkdownProcessor(conf.site.host)
      val htmlCleaner = new HtmlCleaner()
      val templatesDirName = Paths.get(conf.directories.basedir, conf.directories.templates).toString
      val i18nDirName = Paths.get(conf.directories.basedir, conf.directories.templates, "i18n")
      val outputPaths = getOutputPaths(conf)
      val settingsCommonData = Map("title" -> conf.site.title, "description" -> conf.site.description,
        "siteHost" -> conf.site.host, "lastmod" -> conf.site.lastmod)

      def regenerate(): IO[RegenerationError, Unit] = {
        // Rereading content files on every change in case some of them are added/deleted
        val mdContentFilesZ = Task { recursiveListFiles(contentDirFile.toFile).filterNot(_.isDirectory) }

        val translationsZ = translationService.buildTranslationsZ(i18nDirName, outputPaths.siteDir)

        // Making Freemarker re-read templates on every change
        val htmlTemplatesJobZ = Task { templateService.createTemplates(templatesDirName, conf) }
        // Last build date changes on every rebuild
        val siteCommonData: Map[String, Object] = settingsCommonData + ("lastBuildDateJ" -> new JavaDate())

        val cleaningJobZ = Task {
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

        val resultZ: Task[Unit] = for {
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
          result <- Task.collectAll(allJobs).map(_ => ())
        } yield ()

        resultZ.fold(th => RegenerationError(th), identity)
      }

      def fileChanged(file: files.File, action: String): Unit = {
        logger.info(s"File '${file.path.getFileName}' has been $action, regenerating")
        // TODO: Won't work!!
        regenerate()
      }

      class CustomFileMonitor(contentDirFile: Path, recursive: Boolean) extends FileMonitor(contentDirFile, recursive) {
        override def onCreate(file: files.File, count: Int): Unit = fileChanged(file, "created")
        override def onModify(file: files.File, count: Int): Unit = fileChanged(file, "updated")
        override def onDelete(file: files.File, count: Int): Unit = fileChanged(file, "deleted")
        override def onException(exc: Throwable): Unit = logger.error("Exception occurred", exc)
      }

      val withMonitoringZ = if (generationMode != GenerationMode.Once) {

        val serverStartedZ: IO[HttpServerStartError, Option[Server]] =
          if (generationMode != GenerationMode.MonitorNoServer) {
            httpServerService.start(outputPaths.siteDir.toString, conf.server.port).option
          } else IO.succeed(None)

        val monitorRegisteredZ: IO[FileMonitorStartError, FileMonitor] = IO.effectSuspendTotal {
          try {
            logger.info("Registering a file watcher")
            val monitor = new CustomFileMonitor(contentDirFile, recursive = true)
            monitor.start()(ExecutionContext.global)
            logger.info(s"Waiting for changes...")
            IO.succeed(monitor)
          } catch {
            case exc: Exception => IO.fail(FileMonitorStartError(exc))
          }
        }

        def registerShutdownHook(
            maybeServer: Option[Server], fileMonitor: FileMonitor): IO[ShutdownHookRegistrationError, Unit] = {
          try {
            Runtime.getRuntime.addShutdownHook(new Thread() {
              override def run(): Unit = {
                logger.info("Stopping the monitor")
                httpServerService.stop(maybeServer)
              }
            })
            IO.succeed(())
          } catch {
            case exc: Exception => IO.fail(ShutdownHookRegistrationError(exc))
          }
        }

        val watchingStartedZ: IO[SystemError, Unit] = serverStartedZ.flatMap { maybeServer =>
          monitorRegisteredZ.flatMap { monitor =>
            registerShutdownHook(maybeServer, monitor)
          }
        }

        watchingStartedZ
      } else {
        // Waiting only in the "once" mode. In the "monitor" mode, actor system will prevent us from exiting
        UIO.succeed(())
      }
      regenerate() *> withMonitoringZ
    }
  }

  private def recursiveListFiles(f: File): Seq[File] = {
    val these = ArraySeq.unsafeWrapArray(f.listFiles)
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
