package com.appliedscala.generator.services

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.errors.FileMonitorStartError
import org.slf4j.LoggerFactory
import zio.{IO, ZIO}

import java.nio.file.Path
import scala.concurrent.Future
import zio.Task
import zio.blocking._
import zio.stream.ZStream
import com.appliedscala.generator.model.FileChangeEvent

class MonitorService {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerFileWatcher(
      contentDirFile: Path, fileChanged: (files.File, String) => Unit): ZIO[Blocking, FileMonitorStartError, FileMonitor] = {
    blockingExecutor.flatMap { executor =>
      Task {
        logger.info("Registering a file watcher")
        val monitor = new CustomFileMonitor(contentDirFile, fileChanged)
        // TODO: Convert to ZStream[FileChangedEvent(path: String, action: FileChangeAction, when: Long)]
        monitor.start()(executor.asEC)
        logger.info(s"Waiting for changes...")
        monitor
      }
    }.refineOrDie { case th: Throwable => 
      FileMonitorStartError(th)
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
