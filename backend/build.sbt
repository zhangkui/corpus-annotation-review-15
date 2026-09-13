ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "com.corpusreview"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "corpus-review-backend",
    version := "1.0.0",
    libraryDependencies ++= Seq(
      guice,
      jdbc,
      logback,
      filters,
      "org.playframework" %% "play-json" % "3.0.4",
      "org.postgresql" % "postgresql" % "42.7.4",
      "com.rabbitmq" % "amqp-client" % "5.22.0",
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
    ),
    // Scala 3.5 使用 Play 3.0（以 Scala 3.3 LTS 构建，TASTy 向后兼容）
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports"),
    Compile / doc / sources := Seq.empty
  )
