package com.appliedscala.generator.errors

case class ConfigurationParsingException(message: String)
    extends RuntimeException(s"Exception occurred while reading configuration file: $message")
    with S2GenError
