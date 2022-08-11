package com.appliedscala.generator

import com.appliedscala.generator.services._
import jam._
import zio._

object Runner extends ZIOAppDefault {
  class Module {
    private lazy val initService: InitService = brew[InitService]
    private lazy val httpServerService: HttpServerService = brew[HttpServerService]
    private lazy val configurationReadingService: ConfigurationReadingService = brew[ConfigurationReadingService]
    private lazy val translationService: TranslationService = brew[TranslationService]
    private lazy val previewService: PreviewService = brew[PreviewService]
    private lazy val templateService: TemplateService = brew[TemplateService]
    private lazy val pageGenerationService: PageGenerationService = brew[PageGenerationService]
    private lazy val monitorService: MonitorService = brew[MonitorService]
    private lazy val shutdownService: ShutdownService = brew[ShutdownService]
    private lazy val generationService: GenerationService = brew[GenerationService]
    private lazy val commandLineService: CommandLineService = brew[CommandLineService]
    private lazy val markdownService: MarkdownService = brew[MarkdownService]

    def start(args: List[String]): UIO[ExitCode] = {
      generationService.run(args)
    }
  }

  override def run = {
    for {
      args <- getArgs
      module = new Module
      app <- module.start(args.toList)
    } yield app
  }
}
