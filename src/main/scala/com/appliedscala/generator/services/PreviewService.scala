package com.appliedscala.generator.services

class PreviewService {
  private val PreviewSplitter = """\[\/\/\]\: \# \"__PREVIEW__\""""

  def extractPreview(contentMd: String): Option[String] = {
    val contentLength = contentMd.length
    val previewParts = contentMd.split(PreviewSplitter)
    if (previewParts.length > 1 && previewParts(1).trim.length > 0) {
      Some(previewParts(1))
    } else if (previewParts.nonEmpty && previewParts(0).trim.length > 0 && previewParts(0).length < contentLength) {
      Some(previewParts(0))
    } else {
      None
    }
  }

}
