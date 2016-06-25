lazy val settings = Seq(organization := "com.sageserpent",
  name := "plutonium",
  version := "1.0.1-SNAPSHOT",
  scalaVersion := "2.11.8",
  scalacOptions += "-Xexperimental",

  libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.2",
  libraryDependencies += "cglib" % "cglib" % "3.2.0",
  libraryDependencies += "com.sageserpent" %% "americium" % "0.1.0",
  libraryDependencies += "com.jsuereth" %% "scala-arm" % "1.4",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.11.8",
  libraryDependencies += "com.github.etaty" %% "rediscala" % "1.6.0",
  libraryDependencies += "io.github.nicolasstucki" %% "multisets" % "0.3",
  libraryDependencies += "com.twitter" % "chill_2.11" % "0.8.0",

  libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.21" % "provided",
  libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21" % "test",

  libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21" % "test",
  libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.4.7" % "test",

  libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.12.5" % "test",
  libraryDependencies += "org.scalamock" %% "scalamock-scalatest-support" % "3.2" % "test",
  libraryDependencies += "org.scalaz" % "scalaz-scalacheck-binding_2.11" % "7.2.2" % "test",
  libraryDependencies += "com.github.kstyrc" % "embedded-redis" % "0.6" % "test",
  libraryDependencies += "junit" % "junit" % "4.12" % "test",

  publishMavenStyle := true,
  publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath+"/.m2/repository"))))


lazy val plutonium = (project in file(".")).settings(settings: _*)

resolvers += Resolver.jcenterRepo