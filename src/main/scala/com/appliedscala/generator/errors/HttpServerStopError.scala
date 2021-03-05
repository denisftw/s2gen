package com.appliedscala.generator.errors

case class HttpServerStopError(th: Throwable) extends RuntimeException(th) with S2GenError
