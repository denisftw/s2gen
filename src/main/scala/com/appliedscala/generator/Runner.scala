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

object Runner {
  class Module {
    lazy private val commandLineService: CommandLineService = brew[CommandLineService]
    lazy private val initService: InitService = brew[InitService]
    lazy private val configurationReadingService: ConfigurationReadingService = brew[ConfigurationReadingService]
    lazy private val markdownService: MarkdownService = brew[MarkdownService]
    lazy private val translationService: TranslationService = brew[TranslationService]
    lazy private val previewService: PreviewService = brew[PreviewService]
    lazy private val templateService: TemplateService = brew[TemplateService]
    lazy private val pageGenerationService: PageGenerationService = brew[PageGenerationService]
    lazy private val generationService: GenerationService = brew[GenerationService]

    def start(args: Array[String]): Unit = {
      generationService.run(args)
    }
  }

  def main(args: Array[String]): Unit = {
    val module = new Module
    module.start(args)
  }
}
