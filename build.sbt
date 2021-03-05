name := "s2gen"

version := "0.3.4"

scalaVersion := "2.13.5"

scalacOptions += "-target:jvm-1.8"

val circeVersion = "0.12.3"
val monixVersion = "3.3.0"
val flexmarkVersion = "0.62.2"

libraryDependencies ++= Seq(
  "com.vladsch.flexmark" % "flexmark-ext-tables" % flexmarkVersion,
  "com.vladsch.flexmark" % "flexmark-profile-pegdown" % flexmarkVersion,
  "org.freemarker" % "freemarker" % "2.3.31",
  "org.slf4j" % "slf4j-api" % "1.7.19",
  "ch.qos.logback" % "logback-classic" % "1.1.6",
  "com.github.pathikrit" %% "better-files" % "3.9.1",
  "commons-io" % "commons-io" % "2.4",
  "commons-cli" % "commons-cli" % "1.3.1",
  "com.beachape" %% "enumeratum" % "1.6.1",
  "com.typesafe.play" %% "play-json" % "2.9.2",
  "io.monix" %% "monix" % monixVersion,
  "org.eclipse.jetty" % "jetty-server" % "9.4.38.v20210224",
  "net.sourceforge.htmlcleaner" % "htmlcleaner" % "2.24"
)

enablePlugins(JavaAppPackaging)
