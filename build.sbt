ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "com.github.gmlewis"

val spinalVersion = "1.12.3"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib  = "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test

lazy val asicReticulum = (project in file("."))
  .settings(
    name := "asic-reticulum",
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin, scalaTest),
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    Test / scalaSource := baseDirectory.value / "hw" / "sim",
    Test / envVars := Map("CXXFLAGS" -> "-DWData=uint32_t", "CPPFLAGS" -> "-DWData=uint32_t")
  )

fork := true


