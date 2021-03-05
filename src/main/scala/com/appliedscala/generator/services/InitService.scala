package com.appliedscala.generator.services

import org.slf4j.LoggerFactory

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import scala.io.Source

class InitService {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def initProjectStructure(): Unit = {
    println("Initializing...")
    val classLoader = this.getClass.getClassLoader
    copyFromClasspath(classLoader, "init/site/css/styles.css", "site/css", "styles.css")
    copyFromClasspath(classLoader, "init/s2gen.json", ".", "s2gen.json")
    copyFromClasspath(classLoader, "init/content/hello-world.md", "content/blog/2016", "hello-world.md")
    val templateNames = Seq(
      "archive.ftl",
      "blog.ftl",
      "footer.ftl",
      "header.ftl",
      "index.ftl",
      "main.ftl",
      "menu.ftl",
      "page.ftl",
      "post.ftl",
      "sitemap.ftl",
      "about.ftl",
      "info.ftl",
      "feed.ftl"
    )
    templateNames.foreach { templateName =>
      copyFromClasspath(classLoader, s"init/templates/$templateName", "templates", templateName)
    }
    println(s"The skeleton project has been generated. Now you can type s2gen to generate HTML files")
  }

  private def copyFromClasspath(classLoader: ClassLoader, cpPath: String, destinationDir: String,
      destinationFilename: String): Unit = {
    val source = Source.fromInputStream(classLoader.getResourceAsStream(cpPath), "UTF-8")
    val destinationPath = Paths.get(destinationDir)
    if (Files.notExists(destinationPath)) {
      Files.createDirectories(destinationPath)
    }
    val pw = new PrintWriter(new File(destinationDir, destinationFilename))
    try {
      source.foreach(char => pw.print(char))
    } catch {
      case e: Exception => logger.error("Exception occurred while initializing the project", e)
    } finally {
      pw.close()
    }
  }
}
