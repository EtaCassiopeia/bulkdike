val scalaLts        = "3.3.8" // Libraries compile against the LTS so any Scala 3 consumer can use them
val scalaNext       = "3.9.0"
val zioVersion      = "2.1.27"
val rezilienceVersion = "0.10.5"

inThisBuild(
  List(
    organization       := "io.github.etacassiopeia",
    scalaVersion       := scalaLts,
    crossScalaVersions := Seq(scalaLts, scalaNext),
    homepage           := Some(uri("https://github.com/EtaCassiopeia/bulkdike")),
    licenses           := List("Apache-2.0" -> uri("https://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer("EtaCassiopeia", "EtaCassiopeia", "zainalpour@gmail.com", uri("https://github.com/EtaCassiopeia"))
    )
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, examples)
  .settings(
    name           := "bulkdike-root",
    publish / skip := true
  )

lazy val core = project
  .in(file("core"))
  .settings(
    name := "bulkdike",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core)
  .settings(
    name           := "bulkdike-examples",
    publish / skip := true,
    libraryDependencies += "nl.vroste" %% "rezilience" % rezilienceVersion
  )

addCommandAlias("fmt", ";scalafmtSbt;scalafmtAll")
addCommandAlias("check", ";scalafmtSbtCheck;scalafmtCheckAll")
