package com.appliedscala.generator.model

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser

case class MarkdownProcessor(parser: Parser, renderer: HtmlRenderer)
