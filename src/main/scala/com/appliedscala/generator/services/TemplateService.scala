package com.appliedscala.generator.services

import com.appliedscala.generator.configuration.ApplicationConfiguration
import com.appliedscala.generator.model.{CustomHtmlTemplateDescription, CustomXmlTemplateDescription, HtmlTemplates}
import freemarker.template.{Configuration, TemplateExceptionHandler}

import java.io.File

class TemplateService {

  private def createFreemarkerConfig(templateDirName: String): Configuration = {
    val cfg = new Configuration(Configuration.VERSION_2_3_20)
    cfg.setDirectoryForTemplateLoading(new File(templateDirName))
    cfg.setDefaultEncoding("UTF-8")
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER)
    cfg
  }

  def createTemplates(templatesDirName: String, conf: ApplicationConfiguration): HtmlTemplates = {
    val cfg = createFreemarkerConfig(templatesDirName)
    val postTemplate = cfg.getTemplate(conf.templates.post)
    val archiveTemplate = cfg.getTemplate(conf.templates.archive)
    val indexTemplate = cfg.getTemplate(conf.templates.index)
    val customTemplates = conf.templates.custom.map { name =>
      CustomHtmlTemplateDescription(name.replaceAll("\\.ftl$", ""), cfg.getTemplate(name))
    }
    val customXmlTemplates = conf.templates.customXml.map { name =>
      CustomXmlTemplateDescription(name.replaceAll("\\.ftl$", ".xml"), cfg.getTemplate(name))
    }

    HtmlTemplates(postTemplate, archiveTemplate, indexTemplate, customTemplates, customXmlTemplates)
  }
}
