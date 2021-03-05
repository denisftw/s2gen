package com.appliedscala.generator.errors

case class HttpServerStartException(th: Throwable) extends RuntimeException(th) with S2GenError
