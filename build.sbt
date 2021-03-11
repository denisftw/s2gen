name := "s2gen"

version := "0.3.4"

scalaVersion := "2.13.5"

scalacOptions := Seq("-target:jvm-1.8", "-unchecked", "-deprecation")

val flexmarkVersion = "0.62.2"

libraryDependencies ++= Seq(
  "com.github.yakivy" %% "jam-core" % "0.0.2" % Provided,
  "com.vladsch.flexmark" % "flexmark-ext-tables" % flexmarkVersion,
  "com.vladsch.flexmark" % "flexmark-profile-pegdown" % flexmarkVersion,
  "org.freemarker" % "freemarker" % "2.3.31",
  "org.slf4j" % "slf4j-api" % "1.7.19",
  "ch.qos.logback" % "logback-classic" % "1.1.6",
  "com.github.pathikrit" %% "better-files" % "3.9.1",
  "commons-io" % "commons-io" % "2.8.0",
  "commons-cli" % "commons-cli" % "1.3.1",
  "com.beachape" %% "enumeratum" % "1.6.1",
  "com.typesafe.play" %% "play-json" % "2.9.2",
  "dev.zio" %% "zio" % "1.0.5",
  "dev.zio" %% "zio-streams" % "1.0.5", 
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.2",
  "org.eclipse.jetty" % "jetty-server" % "9.4.38.v20210224",
  "net.sourceforge.htmlcleaner" % "htmlcleaner" % "2.24"
)

enablePlugins(JavaAppPackaging)
