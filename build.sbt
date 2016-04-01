name := "s2gen"

version := "0.2.2"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.pegdown" % "pegdown" % "1.6.0",
  "org.freemarker" % "freemarker" % "2.3.21",
  "org.slf4j" % "slf4j-api" % "1.7.19",
  "ch.qos.logback" %  "logback-classic" % "1.1.6",
  "org.scalaz" %% "scalaz-core" % "7.2.1",
  "org.scalaz" %% "scalaz-concurrent" % "7.2.1",
  "com.beachape.filemanagement" %% "schwatcher" % "0.3.1",
  "com.typesafe" % "config" % "1.3.0",
  "commons-io" % "commons-io" % "2.4",
  "commons-cli" % "commons-cli" % "1.3.1"
)

enablePlugins(JavaAppPackaging)
