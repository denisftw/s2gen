package com.appliedscala.generator.services

import com.appliedscala.generator.model.MarkdownProcessor
import com.appliedscala.generator.errors._
import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.renderer.{AttributablePart, NodeRenderer, NodeRenderingHandler}
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.{DataHolder, MutableDataHolder}
import com.vladsch.flexmark.util.html.MutableAttributes
import com.vladsch.flexmark.util.sequence.BasedSequence
import org.htmlcleaner.HtmlCleaner

import java.io.File
import java.nio.file.Paths
import java.util
import scala.io.Source
import zio.{Has, UIO, ZIO}
import zio.blocking._
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.attributes.AttributesExtension
import com.vladsch.flexmark.util.misc.Extension

object MarkdownService {

  private val PropertiesSeparator = "~~~~~~"

  def createMarkdownProcessor(host: String): MarkdownProcessor = {
    val linkRendererExtension = new TargetBlankLinkRendererExtension(host)
    val options = new MutableDataSet() 
    val extensions: util.Collection[Extension] = util.Arrays.asList(TablesExtension.create(), linkRendererExtension)
    options
        .set(Parser.EXTENSIONS, extensions)
        .set(AttributesExtension.FENCED_CODE_INFO_ATTRIBUTES, Boolean.box(true))
    val parser = Parser.builder(options).build()
    val renderer = HtmlRenderer.builder(options).build()
    MarkdownProcessor(parser, renderer)
  }

  def processMdFiles(mdFiles: Seq[File], htmlCleaner: HtmlCleaner, mdProcessor: MarkdownProcessor)
      : ZIO[Blocking with Has[PreviewService], MarkdownGenerationError, Seq[Map[String, String]]] = {
    ZIO
      .foreach(mdFiles) { mdFile =>
        processMdFile(mdFile, htmlCleaner, mdProcessor)
      }
      .catchAll { th =>
        ZIO.fail(MarkdownGenerationError(th))
      }
  }

  private def readPostContent(mdFile: File): ZIO[Blocking, Throwable, List[String]] = {
    blocking {
      ZIO.bracket(ZIO(Source.fromFile(mdFile))) { bufferedSource =>
        UIO(bufferedSource.close())
      } { bufferedSource =>
        ZIO(bufferedSource.getLines().toList)
      }
    }
  }

  private def processMdFile(mdFile: File, htmlCleaner: HtmlCleaner, mdProcessor: MarkdownProcessor)
      : ZIO[Blocking with Has[PreviewService], Throwable, Map[String, String]] = {
    ZIO.service[PreviewService].flatMap { previewService =>
      readPostContent(mdFile).map { postContent =>
        val separatorLineNumber = postContent.indexWhere(_.startsWith(PropertiesSeparator))
        val propertiesLines = postContent.take(separatorLineNumber)
        val contentLines = postContent.drop(separatorLineNumber + 1)

        val contentPropertyMap = propertiesLines.flatMap { propertyLine =>
          val pair = propertyLine.split("=")
          pair match {
            case Array(first, second) => Some(first -> second)
            case _                    => None
          }
        }.toMap
        val mdContent = contentLines.mkString("\n")
        val mdPreview = previewService.extractPreview(mdContent)

        val renderedMdContent = mdProcessor.renderer.render(mdProcessor.parser.parse(mdContent))
        val htmlPreview = mdPreview.map { preview =>
          mdProcessor.renderer.render(mdProcessor.parser.parse(preview))
        }
        val simpleFilename = Paths.get(mdFile.getParentFile.getName, mdFile.getName).toString

        val mapBuilder = Map.newBuilder[String, String]
        mapBuilder ++= contentPropertyMap
        mapBuilder ++= Map(
          "body" -> renderedMdContent,
          "sourceDirectoryPath" -> mdFile.getParentFile.getAbsolutePath,
          "sourceFilename" -> simpleFilename
        )
        htmlPreview.foreach { preview =>
          val previewText = htmlCleaner.clean(preview).getText.toString
          mapBuilder ++= Map(
            "preview" -> preview,
            "previewText" -> previewText
          )
        }
        mapBuilder.result()
      }
    }
  }

  class TargetBlankLinkRendererExtension(siteUrl: String) extends HtmlRenderer.HtmlRendererExtension {
    class TargetBlankLinkRenderer extends NodeRenderer {
      override def getNodeRenderingHandlers: util.Set[NodeRenderingHandler[_]] = {
        val set = new util.HashSet[NodeRenderingHandler[_]]()
        val linkHandler = new NodeRenderingHandler[Link](classOf[Link],
          (node, context, writer) => {
          if (!node.getUrl.startsWith("/") && !node.getUrl.containsAllOf(BasedSequence.of(siteUrl))) {
            val attributes = new MutableAttributes()
            attributes.addValue("target", "_blank")
            val updated = context.extendRenderingNodeAttributes(AttributablePart.NODE, attributes)
            writer.setAttributes(updated).getContext.delegateRender()
          } else {
            context.delegateRender();
          }
        })
        set.add(linkHandler);
        set
      }
    }
    override def rendererOptions(options: MutableDataHolder): Unit = {}
    override def extend(htmlRendererBuilder: HtmlRenderer.Builder, rendererType: String): Unit = {
      htmlRendererBuilder.nodeRendererFactory((_: DataHolder) => new TargetBlankLinkRenderer)
    }
  }
}
