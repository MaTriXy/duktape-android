import app.cash.zipline.gradle.ZiplineCompileTask

plugins {
  id("app.cash.zipline")
}

val compileHello by tasks.creating(ZiplineCompileTask::class) {
  inputJs = file("$projectDir/hello.js")
  inputSourceMap = file("$projectDir/hello.js.map")
  outputZipline = file("$buildDir/zipline/hello.zipline")
}
