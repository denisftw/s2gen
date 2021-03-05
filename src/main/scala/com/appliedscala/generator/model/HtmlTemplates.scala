package com.appliedscala.generator.model

import freemarker.template.Template

case class HtmlTemplates(postTemplate: Template, archiveTemplate: Template, indexTemplate: Template,
    customHtmlTemplates: Seq[CustomHtmlTemplateDescription], customXmlTemplates: Seq[CustomXmlTemplateDescription])
