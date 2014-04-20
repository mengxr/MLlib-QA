name := "mllib-qa"

scalaVersion := "2.10.4"

version := "0.1"

libraryDependencies += "org.apache.spark" %% "spark-mllib" % "1.0.0-SNAPSHOT" % "provided"

libraryDependencies += "org.scalatest" %% "scalatest" % "1.9.1" % "test"
