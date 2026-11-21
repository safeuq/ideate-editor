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
  implementation(files(protocolJars.map { "$jetbrainsClientLib/$it" }))
  // JetBrains Runtime's API (com.jetbrains.JBR): the macOS title bar (MacTitleBar.kt). The same jar
  // IntelliJ ships; it calls into the runtime and reports "not supported" on other Java runtimes.
  implementation(files("$jetbrainsClientLib/intellij.libraries.jbr.jar"))
  implementation(files(fleetJars.map { "$jetbrainsClientLib/$it" }))
  implementation(compose.desktop.currentOs)
  // Dispatchers.Main on the desktop (ConnectController); Compose Desktop doesn't bring it. It also
  // sets coroutines-core: 1.11, which JetBrains' Fleet jars are built against (1.9 lacks
  // SendChannel.close's default-argument bridge they call).
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
  // Pixel tests of the Lux painter need Skia, which only this module has.
  testImplementation(kotlin("test"))
  // DesktopDriverTest: Compose's in-process UI test layer drives the real screens.
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) testImplementation(compose.uiTest)
}

tasks.test {
  useJUnitPlatform()
  // Fonts and icons, as the app reads them (DesktopDriverTest).
  systemProperty("jetbrains.client.lib", jetbrainsClientLib)
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
  compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

compose.desktop {
  application {
    mainClass = "intellijandroid.client.MainKt"
    // Run on the JetBrains Runtime of the same JetBrains Client install, whatever JDK Gradle runs
    // on: the macOS title bar needs JBR's window decorations (MacTitleBar.kt); another runtime
    // falls back to the native title bar. The install keeps it next to lib: `Contents/jbr/Contents/
    // Home` on macOS, `jbr` on Linux and Windows.
    listOf("jbr/Contents/Home", "jbr")
      .map { file(jetbrainsClientLib).resolveSibling(it) }
      .firstOrNull { it.resolve("bin/java").exists() || it.resolve("bin/java.exe").exists() }
      ?.let { javaHome = it.path }
    // Fonts and icons are read from the same JetBrains Client install as the protocol jars.
    jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
  }
}

// `:desktop:hotRun` and `hotRunAsync` (docs/client.md): the same app, reloaded when `:compose` or
// `:desktop` change. The plugin has no setting for which runtime to use, only the Gradle property
// compose.reload.jbr.binary, so the runtime is JAVA_HOME's, and the check below fails fast if it is
// not a JBR 25 or newer.
tasks.withType<org.jetbrains.compose.reload.gradle.ComposeHotRun>().configureEach {
  mainClass.set("intellijandroid.client.MainKt")
  jvmArgs("-Djetbrains.client.lib=$jetbrainsClientLib")
  // hotRun has no --args: -PjoinLink='tcp://…' connects at once, as the app's first argument does.
  providers.gradleProperty("joinLink").orNull?.let { args(it) }
  doFirst {
    val runtime = javaLauncher.get().metadata
    check(runtime.vendor.contains("JetBrains") && runtime.languageVersion.asInt() >= 25) {
      "Hot reload runs on ${runtime.vendor} ${runtime.languageVersion}; set JAVA_HOME to a JetBrains Runtime 25 or newer (IntelliJ's: Contents/jbr/Contents/Home)"
    }
  }
}
