package com.appliedscala.generator.errors

sealed trait ApplicationError extends Product with Serializable
sealed abstract class UserError(val message: String) extends ApplicationError
sealed abstract class SystemError(val cause: Throwable) extends ApplicationError

case class CommandLineError(override val message: String) extends UserError(message)
case class ConfigurationError(override val message: String) extends UserError(message)

case class GenerationError(override val cause: Throwable) extends SystemError(cause)
case class HttpServerStartError(override val cause: Throwable) extends SystemError(cause)
case class HttpServerStopError(override val cause: Throwable) extends SystemError(cause)
