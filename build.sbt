name := "s2gen"

version := "0.3.3"

scalaVersion := "2.13.5"

scalacOptions += "-target:jvm-1.8"

val circeVersion = "0.12.3"
val monixVersion = "3.3.0"

libraryDependencies ++= Seq(
  "com.vladsch.flexmark" % "flexmark-all" % "0.62.2",
  "org.freemarker" % "freemarker" % "2.3.31",
  "org.slf4j" % "slf4j-api" % "1.7.19",
  "ch.qos.logback" %  "logback-classic" % "1.1.6",
  "com.github.pathikrit" %% "better-files" % "3.9.1",
  "commons-io" % "commons-io" % "2.4",
  "commons-cli" % "commons-cli" % "1.3.1",
  "io.monix" %% "monix" % monixVersion,
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.eclipse.jetty" % "jetty-server" % "9.4.38.v20210224",
  "net.sourceforge.htmlcleaner" % "htmlcleaner" % "2.24"
)

enablePlugins(JavaAppPackaging)
