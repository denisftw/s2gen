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

import zio.blocking._
import zio.clock.Clock
import zio.duration._
import zio.ZManaged

class GenerationService(commandLineService: CommandLineService, httpServerService: HttpServerService,
    configurationReadingService: ConfigurationReadingService, markdownService: MarkdownService,
    translationService: TranslationService, pageGenerationService: PageGenerationService,
    templateService: TemplateService, monitorService: MonitorService, shutdownService: ShutdownService) {

  import java.util.{Date => JavaDate}
  private val DebounceTime = 100.millis
  private val logger = LoggerFactory.getLogger(this.getClass)

  def runZ(args: List[String]): URIO[ZEnv, ExitCode] = {
    commandLineService
      .parseCommandLineArgsZ(args.toArray)
      .flatMap(generateIfRequired)
      .catchAll(handleApplicationError)
  }

  private def handleApplicationError(error: ApplicationError): UIO[ExitCode] = {
    UIO {
      error match {
        case userError: UserError     => println(userError.message)
        case systemError: SystemError => logger.error("Exception occurred", systemError.cause)
      }
      ExitCode.failure
    }
  }

  private def generateIfRequired(parsed: Either[Unit, GenerationMode]): ZIO[ZEnv, ApplicationError, ExitCode] = {
    parsed match {
      case Left(_)     => IO.succeed(ExitCode.success)
      case Right(mode) => generate(mode).map(_ => ExitCode.success)
    }
  }

  private def generate(generationMode: GenerationMode): ZIO[ZEnv, ApplicationError, Unit] = {
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

        def regenerate(): ZIO[Blocking, RegenerationError, Unit] = {
          // Rereading content files on every change in case some of them are added/deleted
          val mdContentFilesZ = recursiveListFiles(contentDirFile.toFile)
          val translationsZ = translationService.buildTranslationsZ(i18nDirName, outputPaths.siteDir)
          // Making Freemarker re-read templates on every change
          val htmlTemplatesJobZ = Task { templateService.createTemplates(templatesDirName, conf) }
          // Last build date changes on every rebuild
          val siteCommonData: Map[String, Object] = settingsCommonData + ("lastBuildDateJ" -> new JavaDate())
          val cleanPrevious =
            pageGenerationService.cleanPreviousVersion(outputPaths.archiveOutput, outputPaths.indexOutputDir)
          val mdProcessingJobZ = mdContentFilesZ.map { mdContentFiles =>
            logger.info("Generation started")
            mdContentFiles.map { mdFile =>
              markdownService.processMdFile(mdFile, htmlCleaner, mdProcessor)
            }
          }
          val resultZ = for {
            htmlTemplates <- htmlTemplatesJobZ
            _ <- cleanPrevious
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
          resultZ.catchAll(th => ZIO.succeed(RegenerationError(th)))
        }

        val startMonitoringIfNeeded = if (generationMode != GenerationMode.Once) {
          val startServerIfNeeded = if (generationMode != GenerationMode.MonitorNoServer) {
            httpServerService.start(outputPaths.siteDir.toString, conf.server.port).option
          } else IO.succeed(None)

          val monitorStream = monitorService.registerFileWatcher(contentDirFile).debounce(DebounceTime).tap { event =>
            val FileChangeEvent(path, action, when) = event
            logger.info("File '{}' has been '{}' at {}", path.getFileName, action.toString().toLowerCase(), when)
            if (!path.toFile.getName.startsWith(".")) {
              regenerate()
            } else ZIO.succeed(())
          }

          for {
            maybeServer <- startServerIfNeeded
            monitorFiber <- monitorStream.runDrain.fork
            stopTask <- shutdownService.registerHook(maybeServer)
            stopTaskFiber <- stopTask.fork
            _ <- stopTaskFiber.join
            _ <- monitorFiber.interrupt
          } yield ()
        } else {
          UIO.succeed(())
        }
        regenerate() *> startMonitoringIfNeeded
      }
    }
  }

  private def recursiveListFiles(f: File): ZIO[Blocking, Throwable, Seq[File]] = {
    def loop(f: File): Seq[File] = {
      val these = ArraySeq.unsafeWrapArray(f.listFiles)
      these ++ these.filter(_.isDirectory).flatMap(loop)
    }
    blocking {
      Task {
        loop(f).filterNot(_.isDirectory)
      }
    }
  }

  private def getOutputPaths(conf: ApplicationConfiguration): OutputPaths = {
    val siteDirPath = Paths.get(conf.directories.basedir, conf.directories.output)
    val siteDir = siteDirPath.toString
    val archiveOutput = conf.directories.archive
    val indexOutputDir = Paths.get(siteDir)
    OutputPaths(archiveOutput, indexOutputDir, siteDirPath)
  }
}
