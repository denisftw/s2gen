package com.appliedscala.generator

import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.renderer.{AttributablePart, NodeRenderer, NodeRenderingHandler}
import com.vladsch.flexmark.util.data.{DataHolder, MutableDataHolder}
import com.vladsch.flexmark.util.html.MutableAttributes
import com.vladsch.flexmark.util.sequence.BasedSequence

import java.util

class TargetBlankLinkRendererExtension(siteUrl: String) extends HtmlRenderer.HtmlRendererExtension {
  class TargetBlankLinkRenderer extends NodeRenderer {
    override def getNodeRenderingHandlers: util.Set[NodeRenderingHandler[_]] = {
      val set = new util.HashSet[NodeRenderingHandler[_]]()
      val linkHandler = new NodeRenderingHandler[Link](classOf[Link], (node, context, writer) => {
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
