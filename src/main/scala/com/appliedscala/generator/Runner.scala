package com.appliedscala.generator

import com.appliedscala.generator.services.{
  CommandLineService,
  ConfigurationReadingService,
  GenerationService,
  InitService,
  MarkdownService,
  PageGenerationService,
  PreviewService,
  TemplateService,
  TranslationService
}
import jam.tree.brew
import zio.{ExitCode, UIO, URIO, ZEnv, ZIO}
import com.appliedscala.generator.services.HttpServerService
import zio.Schedule
import zio.clock.Clock
import java.time.LocalDateTime
import zio.duration.Duration

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
    private lazy val generationService: GenerationService = brew[GenerationService]

    def start(environment: ZEnv, args: List[String]): UIO[ExitCode] = {
      generationService.runZ(args).provide(environment)
    }

    def startHttpServer(environment: ZEnv, args: List[String]): UIO[ExitCode] = {
      httpServerService.start(".", 9000).provide(environment).fold(
        sse => {
          sse.cause.printStackTrace()
          ExitCode.failure
        },
        _ => ExitCode.success
      )
    }
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    ZIO.environment[ZEnv].flatMap { env =>
      val module = new Module
      module.start(env, args)
    }
  }
}
