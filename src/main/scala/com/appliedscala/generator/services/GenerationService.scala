package com.appliedscala.generator.services

import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.errors._
import com.appliedscala.generator.model._
import org.htmlcleaner.HtmlCleaner
import org.slf4j.LoggerFactory
import zio.{ExitCode, IO, UIO, URIO, ZEnv}

import java.io._
import java.nio.file._
import zio.ZIO
import zio.blocking._
import zio.duration._

import scala.annotation.tailrec

class GenerationService(commandLineService: CommandLineService, httpServerService: HttpServerService,
    configurationReadingService: ConfigurationReadingService, markdownService: MarkdownService,
    translationService: TranslationService, pageGenerationService: PageGenerationService,
    templateService: TemplateService, monitorService: MonitorService, shutdownService: ShutdownService) {

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
    configurationReadingService.readConfiguration().flatMap { conf =>
      val contentDirFile = Paths.get(conf.directories.basedir, conf.directories.content)
      val mdProcessor = markdownService.createMarkdownProcessor(conf.site.host)
      val htmlCleaner = new HtmlCleaner()
      val templatesDirName = Paths.get(conf.directories.basedir, conf.directories.templates).toString
      val i18nDirName = Paths.get(conf.directories.basedir, conf.directories.templates, "i18n")
      val outputPaths = getOutputPaths(conf)
      val settingsCommonData = Map("title" -> conf.site.title, "description" -> conf.site.description,
        "siteHost" -> conf.site.host, "lastmod" -> conf.site.lastmod)

      def regenerate(): ZIO[Blocking, SystemError, Unit] = {
        for {
          _ <- UIO(logger.info("Generation started"))
          // Rereading content files on every change in case some of them are added/deleted
          contentFiles <- findContentFiles(contentDirFile)
          // Making Freemarker re-read templates on every change
          htmlTemplates <- templateService.createTemplates(templatesDirName, conf)
          // Last build date changes on every rebuild
          siteCommonData = pageGenerationService.addBuildDateInformation(settingsCommonData)
          _ <- pageGenerationService.cleanPreviousVersion(outputPaths.archiveOutput, outputPaths.indexOutputDir)
          postData <- markdownService.processMdFiles(contentFiles, htmlCleaner, mdProcessor)
          translations <- translationService.buildTranslations(i18nDirName, outputPaths.siteDir)
          _ <- pageGenerationService.generateArchivePage(siteCommonData, postData, outputPaths.archiveOutput,
            htmlTemplates.archiveTemplate, translations)
          _ <- pageGenerationService.generateIndexPage(siteCommonData, outputPaths.indexOutputDir,
            htmlTemplates.indexTemplate, translations)
          _ <- pageGenerationService.generateCustomPages(siteCommonData, postData, outputPaths.indexOutputDir,
            htmlTemplates.customHtmlTemplates, htmlTemplates.customXmlTemplates, translations)
          _ <- pageGenerationService.generatePostPages(postData, siteCommonData, outputPaths, htmlTemplates,
            translations)
        } yield ()
      }

      val startMonitoringIfNeeded = ZIO.when(generationMode != GenerationMode.Once) {
        val startServerIfNeeded = if (generationMode != GenerationMode.MonitorNoServer) {
          httpServerService.start(outputPaths.siteDir.toString, conf.server.port).option
        } else IO.succeed(None)

        val monitorStream = monitorService.registerFileWatcher(contentDirFile).debounce(DebounceTime).tap { event =>
          val FileChangeEvent(path, action, when) = event
          logger.info("File '{}' has been '{}' at {}", path.getFileName, action.toString.toLowerCase(), when)
          ZIO.when(!path.toFile.getName.startsWith("."))(regenerate())
        }
        for {
          maybeServer <- startServerIfNeeded
          monitorFiber <- monitorStream.runDrain.fork
          stopApplicationTask <- shutdownService.registerHook(maybeServer)
          stopTaskFiber <- stopApplicationTask.fork
          _ <- stopTaskFiber.join
          _ <- monitorFiber.interrupt
        } yield ()
      }
      regenerate() *> startMonitoringIfNeeded
    }
  }

  private def findContentFiles(root: Path): ZIO[Blocking, GenerationError, Seq[File]] = {
    @tailrec
    def loop(directories: Seq[File], result: Seq[File]): Seq[File] = {
      val (subdirectories, files) = directories.flatMap(_.listFiles).partition(_.isDirectory)
      val updatedResult = result ++ files
      if (subdirectories.isEmpty) {
        updatedResult
      } else {
        loop(subdirectories, updatedResult)
      }
    }
    blocking {
      ZIO {
        loop(Seq(root).filter(Files.isDirectory(_)).map(_.toFile), Nil)
      }.catchAll { th =>
        ZIO.fail(GenerationError(th))
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
