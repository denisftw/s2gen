package com.appliedscala.generator.errors

sealed trait ApplicationError
sealed class UserError(val message: String) extends ApplicationError
sealed class SystemError(val cause: Throwable) extends ApplicationError

case class CommandLineError(override val message: String) extends UserError(message)
case class ConfigurationError(override val message: String) extends UserError(message)

case class GenerationError(override val cause: Throwable) extends SystemError(cause)
case class HttpServerStartError(override val cause: Throwable) extends SystemError(cause)
case class HttpServerStopError(override val cause: Throwable) extends SystemError(cause)
case class FileMonitorError(override val cause: Throwable) extends SystemError(cause)
case class ShutdownHookRegistrationError(override val cause: Throwable) extends SystemError(cause)
case class RegenerationError(override val cause: Throwable) extends SystemError(cause)
case class InitError(override val cause: Throwable) extends SystemError(cause)
case class TemplateEngineError(override val cause: Throwable) extends SystemError(cause)
case class TranslationBuildingError(override val cause: Throwable) extends SystemError(cause)
case class MarkdownGenerationError(override val cause: Throwable) extends SystemError(cause)
