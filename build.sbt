import AssemblyKeys._

assemblySettings

assemblyOption in assembly ~= { _.copy(includeScala = false) }

name := "mllib-qa"

scalaVersion := "2.10.4"

version := "0.1"

resolvers += Resolver.sonatypeRepo("public")

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += 
  "Spark 1.0 RC" at "https://repository.apache.org/content/repositories/orgapachespark-1010"

resolvers += "Akka Repository" at "http://repo.akka.io/releases/"

libraryDependencies += "org.apache.spark" %% "spark-mllib" % "1.0.0-SNAPSHOT" % "provided"

libraryDependencies += "org.scalatest" %% "scalatest" % "1.9.1" % "test"

libraryDependencies += "com.github.scopt" %% "scopt" % "3.2.0"

libraryDependencies += "com.github.fommil.netlib" % "all" % "1.1.2"

libraryDependencies += "org.scala-lang" %% "scala-pickling" % "0.8.0-SNAPSHOT"

TaskKey[Unit]("writeClasspath") <<= (baseDirectory, 
    externalDependencyClasspath in Compile,
    externalDependencyClasspath in Runtime, 
    packagedArtifact in (Compile, packageBin)).map { (base, compileClasspath, runtimeClasspath, art) =>
      IO.write(base / "target" / "runtimeClasspath", (runtimeClasspath.files.map(_.toString) :+ art._2.getAbsolutePath).mkString(","))
      IO.write(base / "target" / "compileClasspath", (compileClasspath.files.map(_.toString) :+ art._2.getAbsolutePath).mkString(":"))
    }
