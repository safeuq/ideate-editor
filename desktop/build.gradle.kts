import java.nio.file.Files
import org.jetbrains.compose.reload.gradle.ComposeHotRun
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  alias(libs.plugins.compose.hot.reload)
}

val jetbrainsClientLib: String by rootProject.extra
val protocolJars: List<String> by rootProject.extra
val fleetJars: List<String> by rootProject.extra

dependencies {
  implementation(project(":compose"))
  implementation("com.hierynomus:sshj:0.41.2")
  implementation(files(protocolJars.map { "$jetbrainsClientLib/$it" }))
  // JetBrains Runtime's API (com.jetbrains.JBR): the macOS title bar (MacTitleBar.kt). The same jar
  // IntelliJ ships; it calls into the runtime and reports "not supported" on other Java runtimes.
  implementation(files("$jetbrainsClientLib/intellij.libraries.jbr.jar"))
  implementation(files(fleetJars.map { "$jetbrainsClientLib/$it" }))
  implementation(compose.desktop.currentOs)
  // Dispatchers.Main on the desktop (ConnectController); Compose Desktop doesn't bring it. It also
  // sets coroutines-core: 1.11, which JetBrains' Fleet jars are built against (1.9 lacks
  // SendChannel.close's default-argument bridge they call).
  implementation(libs.kotlinx.coroutines.swing)
  // JsonElement for AgentMcpServer; the version :compose already uses.
  implementation(libs.kotlinx.serialization.json)
  // Pixel tests of the Lux painter need Skia, which only this module has.
  testImplementation(kotlin("test"))
  // DesktopDriverTest: Compose's in-process UI test layer drives the real screens.
  testImplementation(libs.compose.ui.test)
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
  compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

// macOS 26 takes the title bar's layout from the SDK the app's executable was built with: JBR's
// `java` (SDK 13.3) gets the older 28 pt bar with 20 pt traffic lights, so JBR's full-screen
// buttons
// sit 2 pt above the toolbar's centre line; IntelliJ's own launcher (`Contents/MacOS/idea`, SDK 26)
// gets a 32 pt bar with 14 pt buttons (docs/intellij-look.md section 25). Until Ideate has its own
// launcher, the app runs on a copy of JBR's `java` marked as built with SDK 26, in a folder that
// links everything else back to the JetBrains Client's JBR, which stays untouched.
val clientJbrHome = file(jetbrainsClientLib).resolveSibling("jbr/Contents/Home")
val sdk26JbrHome = layout.buildDirectory.dir("jbr-sdk26/Home")
val isMac = System.getProperty("os.name").startsWith("Mac")
val prepareSdk26Jbr by tasks.registering {
  description = "Links the client's JBR with a java launcher marked as built with macOS SDK 26"
  onlyIf { isMac }
  inputs.property("clientJbrHome", clientJbrHome.path)
  // Only the launcher is declared, so Gradle never cleans the folder: its links point into the
  // client's JBR, and a delete that follows them empties that JBR.
  outputs.file(sdk26JbrHome.map { it.file("bin/java") })
  doLast {
    check(clientJbrHome.resolve("bin/java").isFile) { "No JBR at $clientJbrHome" }
    val target = sdk26JbrHome.get().asFile
    // Never File.deleteRecursively here: it follows the links into the client's JBR and empties it.
    fun removeWithoutFollowingLinks(file: File) {
      val path = file.toPath()
      if (!Files.isSymbolicLink(path) && file.isDirectory) {
        file.listFiles()!!.forEach(::removeWithoutFollowingLinks)
      }
      Files.deleteIfExists(path)
    }
    removeWithoutFollowingLinks(target)
    target.resolve("bin").mkdirs()
    clientJbrHome
      .listFiles()!!
      .filter { it.name != "bin" }
      .forEach {
        Files.createSymbolicLink(target.resolve(it.name).toPath(), it.toPath())
      }
    clientJbrHome
      .resolve("bin")
      .listFiles()!!
      .filter { it.name != "java" }
      .forEach {
        Files.createSymbolicLink(target.resolve("bin/${it.name}").toPath(), it.toPath())
      }
    val launcher = target.resolve("bin/java")
    fun run(vararg command: String) {
      val process = ProcessBuilder(*command).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
    }
    // vtool comes with Xcode's command line tools (xcode-select --install).
    run(
      "xcrun",
      "vtool",
      "-set-build-version",
      "macos",
      "11.0",
      "26.0",
      "-replace",
      "-output",
      launcher.path,
      clientJbrHome.resolve("bin/java").path,
    )
    run("codesign", "--force", "--sign", "-", launcher.path)
  }
}

compose {
  desktop {
    application {
      mainClass = "io.github.safeuq.ideate.client.MainKt"
      // Run on the JetBrains Runtime of the same JetBrains Client install, whatever JDK Gradle runs
      // on: the macOS title bar needs JBR's window decorations (MacTitleBar.kt); another runtime
      // falls back to the native title bar. The install keeps it next to lib:
      // `Contents/jbr/Contents/Home` on macOS, `jbr` on Linux and Windows.
      listOf("jbr/Contents/Home", "jbr")
        .map { file(jetbrainsClientLib).resolveSibling(it) }
        .firstOrNull { it.resolve("bin/java").exists() || it.resolve("bin/java.exe").exists() }
        ?.let { javaHome = it.path }
      if (isMac) javaHome = sdk26JbrHome.get().asFile.path
      // Fonts and icons are read from the same JetBrains Client install as the protocol jars.
      jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
      providers.gradleProperty("agentMcpPort").orNull?.let {
        jvmArgs("-Dideate.agent.mcp.port=$it")
      }
    }
  }
}

tasks {
  // Every Compose task that reads javaHome needs the SDK 26 folder first; `run` is a plain
  // JavaExec.
  matching {
    it.name == "run" ||
      it.javaClass.name.startsWith("org.jetbrains.compose.desktop.application.tasks.")
  }
    .configureEach { if (isMac) dependsOn(prepareSdk26Jbr) }

  test {
    useJUnitPlatform()
    // Fonts and icons, as the app reads them (DesktopDriverTest).
    systemProperty("jetbrains.client.lib", jetbrainsClientLib)
  }

  // `:desktop:hotRun` and `hotRunAsync` (docs/client.md): the same app, reloaded when `:compose` or
  // `:desktop` change. The plugin has no setting for which runtime to use, only the Gradle property
  // compose.reload.jbr.binary, so the runtime is JAVA_HOME's, and the check below fails fast if it
  // is not a JBR 25 or newer.
  withType<ComposeHotRun>().configureEach {
    mainClass = compose.desktop.application.mainClass
    jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
    // hotRun has no --args: -PjoinLink='tcp://…' connects at once, as the app's first argument
    // does.
    providers.gradleProperty("joinLink").orNull?.let { args(it) }
    // -PagentMcpPort=5979 turns on the agent MCP server (docs/client.md); off without it.
    providers.gradleProperty("agentMcpPort").orNull?.let { jvmArgs("-Dideate.agent.mcp.port=$it") }
    doFirst {
      val runtime = javaLauncher.get().metadata
      check(runtime.vendor.contains("JetBrains") && runtime.languageVersion.asInt() >= 25) {
        "Hot reload runs on ${runtime.vendor} ${runtime.languageVersion}; set JAVA_HOME to a JetBrains Runtime 25 or newer (IntelliJ's: Contents/jbr/Contents/Home)"
      }
    }
  }
}
