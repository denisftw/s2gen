package com.appliedscala.generator

import com.appliedscala.generator.services._
import jam._
import zio.{ExitCode, UIO, URIO, ZEnv, ZIO, ZLayer}

object Runner extends zio.App {
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

    def start(args: List[String]): URIO[ZEnv, ExitCode] = {
      generationService.runZ(args).provideSomeLayer[ZEnv](ZLayer.succeed(previewService) ++ ZLayer.succeed(initService))
    }
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val module = new Module
    module.start(args)
  }
}
