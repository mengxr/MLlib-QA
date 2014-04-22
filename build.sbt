name := "mllib-qa"

scalaVersion := "2.10.4"

version := "0.1"

resolvers += Resolver.sonatypeRepo("public")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += 
  "Spark 1.0 RC" at "https://repository.apache.org/content/repositories/orgapachespark-1010"

resolvers += "Akka Repository" at "http://repo.akka.io/releases/"

libraryDependencies += "org.apache.spark" %% "spark-mllib" % "1.0.0" % "provided"

libraryDependencies += "org.scalatest" %% "scalatest" % "1.9.1" % "test"

libraryDependencies += "com.github.scopt" %% "scopt" % "3.2.0"

// libraryDependencies += "org.scala-lang" %% "scala-pickling" % "0.8.0-SNAPSHOT"
