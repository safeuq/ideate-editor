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
      // Fonts and icons are read from the same JetBrains Client install as the protocol jars.
      jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
      providers.gradleProperty("agentMcpPort").orNull?.let {
        jvmArgs("-Dideate.agent.mcp.port=$it")
      }
    }
  }
}

tasks {
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
