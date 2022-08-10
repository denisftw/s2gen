package com.appliedscala.generator.services

import better.files
import better.files.FileMonitor
import com.appliedscala.generator.errors._
import org.slf4j.LoggerFactory

import java.nio.file.Path
import zio._
import zio.stream._
import java.lang.{System => JavaSystem}
import com.appliedscala.generator.model.FileChangeEvent
import com.appliedscala.generator.model.FileChangeAction

class MonitorService {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def registerFileWatcher(dirFiles: Seq[Path]): ZStream[Any, FileMonitorError, FileChangeEvent] = {
    ZStream.asyncZIO[Any, FileMonitorError, FileChangeEvent] { pushMessage =>
      ZIO.blockingExecutor
        .flatMap { executor =>
          ZIO.attempt {
            logger.info("Registering a file watcher")
            def fileChanged(file: files.File, action: FileChangeAction): Unit = {
              pushMessage(ZIO.succeed(Chunk(FileChangeEvent(file.path, action, JavaSystem.currentTimeMillis()))))
            }
            def exceptionOccurred(th: Throwable): Unit = {
              pushMessage(ZIO.fail(Some(FileMonitorError(th))))
            }
            dirFiles.foreach { dirFile =>
              val monitor = new CustomFileMonitor(dirFile, fileChanged, exceptionOccurred)
              monitor.start()(executor.asExecutionContext)
            }
            logger.info(s"Waiting for changes...")
          }
        }
        .catchAll { th =>
          ZIO.fail(FileMonitorError(th))
        }
    }
  }

  class CustomFileMonitor(contentDirFile: Path, fileChanged: (files.File, FileChangeAction) => Unit,
      exceptionOccurred: Throwable => Unit)
      extends FileMonitor(contentDirFile, recursive = true) {
    override def onCreate(file: files.File, count: Int): Unit = fileChanged(file, FileChangeAction.Created)
    override def onModify(file: files.File, count: Int): Unit = fileChanged(file, FileChangeAction.Updated)
    override def onDelete(file: files.File, count: Int): Unit = fileChanged(file, FileChangeAction.Deleted)
    override def onException(th: Throwable): Unit = exceptionOccurred(th)
  }
}
