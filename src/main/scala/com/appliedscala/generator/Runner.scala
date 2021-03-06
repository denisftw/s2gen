package com.appliedscala.generator

import com.appliedscala.generator.services._
import jam.tree.brew
import zio.{ExitCode, UIO, URIO, ZEnv, ZIO}

object Runner extends zio.App {
  class Module {
    private lazy val commandLineService: CommandLineService = brew[CommandLineService]
    private lazy val initService: InitService = brew[InitService]
    private lazy val httpServerService: HttpServerService = brew[HttpServerService]
    private lazy val configurationReadingService: ConfigurationReadingService = brew[ConfigurationReadingService]
    private lazy val markdownService: MarkdownService = brew[MarkdownService]
    private lazy val translationService: TranslationService = brew[TranslationService]
    private lazy val previewService: PreviewService = brew[PreviewService]
    private lazy val templateService: TemplateService = brew[TemplateService]
    private lazy val pageGenerationService: PageGenerationService = brew[PageGenerationService]
    private lazy val monitorService: MonitorService = brew[MonitorService]
    private lazy val shutdownService: ShutdownService = brew[ShutdownService]
    private lazy val generationService: GenerationService = brew[GenerationService]

    def start(args: List[String]): URIO[ZEnv, ExitCode] = {
      generationService.runZ(args)
    }
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    ZIO.effectSuspendTotal {
      val module = new Module
      module.start(args)
    }
  }
}
