import Dependencies._

val commonScalacOptions = Seq(
  "-encoding",
  "utf8",
  "-target:jvm-1.8",
  "-feature",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-language:higherKinds",
  "-unchecked",
  "-deprecation",
  "-Xlog-reflective-calls"
)

lazy val defaultProjectSettings =
  Seq(
    organization := "io.kagera",
    crossScalaVersions := Seq("2.13.6", "2.12.14"),
    scalaVersion := crossScalaVersions.value.head,
    githubOwner := "xencura",
    githubRepository := "kagera",
    githubTokenSource := TokenSource.GitConfig("github.token"),
    scalacOptions := commonScalacOptions
  )

githubTokenSource := TokenSource.GitConfig("github.token")

lazy val api = crossProject(JSPlatform, JVMPlatform)
  //.withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("api"))
  .settings(defaultProjectSettings: _*)
  .settings(
    name := "kagera-api",
    libraryDependencies ++= Seq(
      collectionCompat,
      scalaGraph.value,
      catsCore.value,
      catsEffect.value,
      fs2Core.value,
      scalatest % "test"
    )
  )
  .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
  .jsSettings(scalaJSLinkerConfig ~= {
    _.withModuleKind(ModuleKind.CommonJSModule)
  })

lazy val visualization = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .enablePlugins(ScalaJSBundlerPlugin)
  .in(file("visualization"))
  .dependsOn(api, execution)
  .settings(defaultProjectSettings: _*)
  .settings(
    name := "kagera-visualization",
    resolvers += "jitpack" at "https://jitpack.io",
    libraryDependencies ++= Seq(
      scalaGraph.value,
      "com.lihaoyi" %%% "scalatags" % "0.9.1",
      "com.lihaoyi" %%% "upickle" % "1.1.0"
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "1.0.0",
      "com.github.xencura.scala-js-d3v4" %%% "scala-js-d3v4" % "766d13e0c1",
      "com.raquo" %%% "laminar" % "0.11.0"
    )
    //Compile / npmDependencies ++= Seq("d3" -> "6.2.0", "@types/d3" -> "6.2.0")
  )
  .jvmSettings(libraryDependencies ++= Seq(scalaGraphDot))

lazy val execution = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(ScalaJSBundlerPlugin)
  .in(file("execution"))
  .dependsOn(api)
  .settings(
    defaultProjectSettings ++ Seq(
      name := "kagera-execution",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        scalaGraph.value,
        scalatest % "test"
      )
    )
  )

lazy val akka = project
  .in(file("akka"))
  .dependsOn(api.jvm, execution.jvm)
  .settings(
    defaultProjectSettings ++ Seq(
      name := "kagera-akka",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        akkaActor,
        akkaPersistence,
        akkaSlf4j,
        akkaStream,
        akkaQuery,
        scalaGraph.value,
        akkaInmemoryJournal % "test",
        akkaTestkit % "test",
        scalatest % "test"
      ),
      PB.protocVersion := "-v3.15.1",
      Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value)
    )
  )

lazy val zio = crossProject(JSPlatform, JVMPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("zio"))
  .dependsOn(api, execution)
  .settings(
    defaultProjectSettings ++
      // Workaround for https://github.com/portable-scala/sbt-crossproject/issues/74
      Seq(Compile, Test).flatMap(inConfig(_) {
        unmanagedResourceDirectories ++= {
          unmanagedSourceDirectories.value
            .map(src => (src / ".." / "resources").getCanonicalFile)
            .filterNot(unmanagedResourceDirectories.value.contains)
            .distinct
        }
      }) ++
      Seq(
        name := "kagera-zio",
        resolvers ++= Seq(
          "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
          Resolver.githubPackages("xencura")
        ),
        libraryDependencies ++= Seq(
          "org.scala-lang" % "scala-reflect" % scalaVersion.value,
          catsCore.value,
          zioCore.value,
          zioStreams.value,
          zioInteropCats.value,
          zioActors.value,
          zioActorsPersistence.value,
          scalaGraph.value,
          zioTest.value % "test",
          zioTestSbt.value % "test"
        ),
        testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
      )
  )

lazy val demo = (crossProject(JSPlatform, JVMPlatform) in file("demo"))
  .enablePlugins(JSDependenciesPlugin, ScalaJSBundlerPlugin)
  .dependsOn(api, visualization)
  .settings(defaultProjectSettings: _*)
  .settings(
    Compile / unmanagedSourceDirectories += baseDirectory.value / "shared" / "main" / "scala",
    libraryDependencies ++= Seq("com.lihaoyi" %%% "scalatags" % "0.9.1", "com.lihaoyi" %%% "upickle" % "1.1.0")
  )
  .jsSettings(
    webpackBundlingMode := BundlingMode.LibraryAndApplication(),
    jsDependencies ++= Seq(
      "org.webjars.bower" % "cytoscape" % cytoscapeVersion
        / s"$cytoscapeVersion/dist/cytoscape.js"
        minified s"$cytoscapeVersion/dist/cytoscape.min.js"
        commonJSName "cytoscape"
    ),
    libraryDependencies ++= Seq("org.scala-js" %%% "scalajs-dom" % "1.0.0")
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "de.heikoseeberger" %% "akka-http-upickle" % "1.32.0",
      akkaHttp,
      akkaQuery,
      akkaPersistenceCassandra
    ),
    name := "demo-app",
    mainClass := Some("io.kagera.demo.Main")
  )

lazy val demoJs = demo.js
lazy val demoJvm = demo.jvm
  .dependsOn(api.jvm, visualization.jvm, akka)
  .settings(
    // include the compiled javascript result from js module
    Compile / resources += (demoJs / Compile / fastOptJS).value.data,
    // include the javascript dependencies
    Compile / resources += (demoJs / Compile / packageJSDependencies).value
  )

lazy val root = Project("kagera", file("."))
  .aggregate(api.jvm, akka, execution.jvm, visualization.jvm, zio.jvm)
  .enablePlugins(BuildInfoPlugin)
  .settings(defaultProjectSettings)
  .settings(
    publish := {},
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      BuildInfoKey.map(git.gitHeadCommit) { case (key, value) =>
        key -> value.getOrElse("-")
      },
      BuildInfoKey.action("buildTime") {
        buildTime
      }
    )
  )

def buildTime = {
  import java.text.SimpleDateFormat
  import java.util._

  val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS '(UTC)'")
  sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
  sdf.format(new Date())
}
