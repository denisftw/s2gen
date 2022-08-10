package com.appliedscala.generator

import com.appliedscala.generator.services._
import jam._
import zio._

object Runner extends ZIOAppDefault {
  class Module {
    private lazy val initService: InitService = brewRec[InitService]
    private lazy val httpServerService: HttpServerService = brewRec[HttpServerService]
    private lazy val configurationReadingService: ConfigurationReadingService = brewRec[ConfigurationReadingService]
    private lazy val translationService: TranslationService = brewRec[TranslationService]
    private lazy val previewService: PreviewService = brewRec[PreviewService]
    private lazy val templateService: TemplateService = brewRec[TemplateService]
    private lazy val pageGenerationService: PageGenerationService = brewRec[PageGenerationService]
    private lazy val monitorService: MonitorService = brewRec[MonitorService]
    private lazy val shutdownService: ShutdownService = brewRec[ShutdownService]
    private lazy val generationService: GenerationService = brewRec[GenerationService]
    private lazy val commandLineService: CommandLineService = brewRec[CommandLineService]

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
