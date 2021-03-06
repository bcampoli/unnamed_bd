//@see https://github.com/marcuslonnberg/sbt-docker
//@see https://github.com/marcuslonnberg/sbt-docker/blob/master/examples/package-spray/build.sbt
//@see https://velvia.github.io/Docker-Scala-Sbt/

import sbt.Keys.{artifactPath, libraryDependencies, mainClass, managedClasspath, name, organization, packageBin, resolvers, version}
import com.typesafe.config.{ConfigFactory, Config}
import java.util.Properties

val appProperties = settingKey[Properties]("The application properties")
appProperties := {
  val prop = new Properties()
  IO.load(prop, new File("../../properties/configuration.properties"))
  prop
}

logLevel := Level.Debug

val rootName = "smart-meter"
name := "docker-" + rootName + "-monitor"
organization := "logimethods"
val tag = "monitor-DEV"

version := "0.4.0-SNAPSHOT"
scalaVersion := "2.11.8"

lazy val scalaNatsVersion = settingKey[String]("scalaNatsVersion")
scalaNatsVersion := {
  try {
    appProperties.value.getProperty("scala_nats_version")
  } catch {
    case _: Exception => "<empty>"
  }
}

lazy val javaNatsStreamingVersion = settingKey[String]("javaNatsStreamingVersion")
javaNatsStreamingVersion := {
  try {
    appProperties.value.getProperty("java_nats_streaming_version")
  } catch {
    case _: Exception => "<empty>"
  }
}

libraryDependencies += "com.github.tyagihas" % "scala_nats_2.11" 			% scalaNatsVersion.value
libraryDependencies += "io.nats"     		 		 % "java-nats-streaming" 	% javaNatsStreamingVersion.value

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += "Sonatype OSS Release" at "https://oss.sonatype.org/content/groups/public/"

enablePlugins(DockerPlugin)

imageNames in docker := Seq(
  ImageName(s"${organization.value}/${rootName}:${tag}")
)

// Define a Dockerfile
dockerfile in docker := {
  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath = (managedClasspath in Compile).value
  val mainclass = mainClass.in(Compile, packageBin).value.getOrElse(sys.error("Expected exactly one main class"))
  val jarTarget = s"/app/${jarFile.getName}"
  // Make a colon separated classpath with the JAR file
  val classpathString = classpath.files.map("/app/" + _.getName)
    .mkString(":") + ":" + jarTarget

  new Dockerfile {
    // Use a base image that contain Scala
	from("frolvlad/alpine-scala:2.11")

    // Add all files on the classpath
    add(classpath.files, "/app/")
    // Add the JAR file
    add(jarFile, jarTarget)
    // On launch run Scala with the classpath and the main class
    entryPoint("scala", "-cp", classpathString, mainclass)
  }
}

// sbt dockerFileTask
// See https://github.com/marcuslonnberg/sbt-docker/issues/34

val dockerFileTask = taskKey[Unit]("Prepare the dockerfile and needed files")

dockerFileTask := {
  val dockerDir = target.value / "docker"
  val artifact: File = assembly.value
  val artifactTargetPath = s"/app/${artifact.name}"

  val jarFile: File = sbt.Keys.`package`.in(Compile, packageBin).value
  val classpath = (managedClasspath in Compile).value
  val mainclass = mainClass.in(Compile, packageBin).value.getOrElse(sys.error("Expected exactly one main class"))
  val jarTarget = s"/app/${jarFile.getName}"
  // Make a colon separated classpath with the JAR file
  val classpathString = classpath.files.map("/app/" + _.getName)
    .mkString(":") + ":" + jarTarget

  val dockerFile = new Dockerfile {
    // Use a base image that contain Scala
	from("frolvlad/alpine-scala:2.11")

    // Add all files on the classpath
    add(classpath.files, "/app/")
    // Add the JAR file
    add(jarFile, jarTarget)
    // On launch run Scala with the classpath and the main class
    entryPoint("scala", "-cp", classpathString, mainclass)
  }

  val stagedDockerfile =  sbtdocker.staging.DefaultDockerfileProcessor(dockerFile, dockerDir)
  IO.write(dockerDir / "Dockerfile",stagedDockerfile.instructionsString)
  stagedDockerfile.stageFiles.foreach {
    case (source, destination) =>
      source.stage(destination)
  }
}

dockerFileTask <<= dockerFileTask.dependsOn(compile in Compile, dockerfile in docker)
