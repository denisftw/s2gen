package com.appliedscala.generator.services

import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors._
import com.appliedscala.generator.model._
import org.eclipse.jetty.server.Server
import org.htmlcleaner.HtmlCleaner
import org.slf4j.LoggerFactory
import zio.{ExitCode, IO, Task, UIO, URIO, ZEnv}

import java.io._
import java.nio.file._
import scala.collection.compat.immutable.ArraySeq
import zio.ZIO

import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._

class GenerationService(commandLineService: CommandLineService, httpServerService: HttpServerService,
    configurationReadingService: ConfigurationReadingService, markdownService: MarkdownService,
    translationService: TranslationService, pageGenerationService: PageGenerationService,
    templateService: TemplateService, monitorService: MonitorService, shutdownService: ShutdownService) {

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

  private def generateIfRequired(parsed: Either[Unit, GenerationMode]): ZIO[ZEnv, ApplicationError, ExitCode] = {
    ZIO.accessM[ZEnv] { environment =>
      parsed match {
        case Left(_)     => IO.succeed(ExitCode.success)
        case Right(mode) => generate(environment, mode).map(_ => ExitCode.success)
      }
    }
  }

  private def generate(
      environment: ZEnv, generationMode: GenerationMode): ZIO[Blocking with Clock, ApplicationError, Unit] = {
    ZIO.effectSuspendTotal {
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
          val cleaningJobZ =
            pageGenerationService.cleanPreviousVersion(outputPaths.archiveOutput, outputPaths.indexOutputDir)
          val mdProcessingJobZ = mdContentFilesZ.map { mdContentFiles =>
            logger.info("Generation started")
            mdContentFiles.map { mdFile =>
              markdownService.processMdFile(mdFile, htmlCleaner, mdProcessor)
            }
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

        val startMonitoringIfNeeded = if (generationMode != GenerationMode.Once) {
          val serverStartedZ: IO[HttpServerStartError, Option[Server]] =
            if (generationMode != GenerationMode.MonitorNoServer) {
              httpServerService.start(outputPaths.siteDir.toString, conf.server.port).provide(environment).option
            } else IO.succeed(None)

          val monitorStream = monitorService.registerFileWatcher(contentDirFile).debounce(100.millis).tap { event =>
            val FileChangeEvent(path, action, when) = event
            logger.info(s"File '${path.getFileName}' has been '$action' at $when")
            if (!path.toFile.getName.startsWith(".")) {
              regenerate()
            } else ZIO.succeed(())
          }
          val watchingStartedZ: ZIO[Blocking with Clock, SystemError, Unit] = serverStartedZ.flatMap { maybeServer =>
            monitorStream.runDrain.fork.flatMap { monitorFiber =>
              shutdownService.registerHook(maybeServer)
            }
          }

          watchingStartedZ *> IO.never.as(())
        } else {
          UIO.succeed(())
        }
        regenerate() *> startMonitoringIfNeeded
      }
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
