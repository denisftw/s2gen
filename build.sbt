name := "s2gen"

version := "0.2.7"

scalaVersion := "2.11.8"

scalacOptions += "-target:jvm-1.6"

val circeVersion = "0.5.2"
val monixVersion = "2.0.1"

libraryDependencies ++= Seq(
  "org.pegdown" % "pegdown" % "1.6.0",
  "org.freemarker" % "freemarker" % "2.3.23",
  "org.slf4j" % "slf4j-api" % "1.7.19",
  "ch.qos.logback" %  "logback-classic" % "1.1.6",
  "com.beachape.filemanagement" %% "schwatcher" % "0.3.1",
  "commons-io" % "commons-io" % "2.4",
  "commons-cli" % "commons-cli" % "1.3.1",
  "io.monix" %% "monix" % monixVersion,
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "org.eclipse.jetty" % "jetty-server" % "9.3.11.v20160721"
)

enablePlugins(JavaAppPackaging)
