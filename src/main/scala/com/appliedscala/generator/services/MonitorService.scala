package com.appliedscala.generator.services

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.errors.FileMonitorStartError
import org.slf4j.LoggerFactory
import zio.{IO, ZIO}

import java.nio.file.Path
import scala.concurrent.Future

class MonitorService {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerFileWatcher(
      contentDirFile: Path, fileChanged: (files.File, String) => Unit): IO[FileMonitorStartError, FileMonitor] =
    IO.effectSuspendTotal {
      try {
        logger.info("Registering a file watcher")
        val monitor = new CustomFileMonitor(contentDirFile, fileChanged)
        ZIO
          .fromFuture { implicit executionContext =>
            monitor.start()
            logger.info(s"Waiting for changes...")
            Future.successful(monitor)
          }
          .catchAll(th => IO.fail(FileMonitorStartError(th)))
      } catch {
        case exc: Exception => IO.fail(FileMonitorStartError(exc))
      }
    }

  class CustomFileMonitor(contentDirFile: Path, fileChanged: (files.File, String) => Unit)
      extends FileMonitor(contentDirFile, recursive = true) {
    override def onCreate(file: files.File, count: Int): Unit = fileChanged(file, "created")
    override def onModify(file: files.File, count: Int): Unit = fileChanged(file, "updated")
    override def onDelete(file: files.File, count: Int): Unit = fileChanged(file, "deleted")
    override def onException(exc: Throwable): Unit = logger.error("Exception occurred", exc)
  }
}
