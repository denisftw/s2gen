name := "s2gen"

version := "0.3.7"

scalaVersion := "2.13.7"

scalacOptions := Seq("-target:jvm-1.8", "-unchecked", "-deprecation")

val flexmarkVersion = "0.62.2"

libraryDependencies ++= Seq(
  "com.github.yakivy" %% "jam-core" % "0.1.0" % Provided,
  "com.vladsch.flexmark" % "flexmark-ext-tables" % flexmarkVersion,
  "com.vladsch.flexmark" % "flexmark-profile-pegdown" % flexmarkVersion,
  "org.freemarker" % "freemarker" % "2.3.31",
  "org.slf4j" % "slf4j-api" % "1.7.32",
  "ch.qos.logback" % "logback-classic" % "1.2.10",
  "com.github.pathikrit" %% "better-files" % "3.9.1",
  "commons-io" % "commons-io" % "2.11.0",
  "commons-cli" % "commons-cli" % "1.5.0",
  "com.beachape" %% "enumeratum" % "1.7.0",
  "com.typesafe.play" %% "play-json" % "2.9.2",
  "dev.zio" %% "zio" % "1.0.12",
  "dev.zio" %% "zio-streams" % "1.0.12",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.6.0",
  "org.eclipse.jetty" % "jetty-server" % "9.4.44.v20210927",
  "net.sourceforge.htmlcleaner" % "htmlcleaner" % "2.25"
)

enablePlugins(JavaAppPackaging)
