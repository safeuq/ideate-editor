import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
}

val jetbrainsClientLib: String by rootProject.extra
val protocolJars: List<String> by rootProject.extra

dependencies {
  implementation(project(":compose"))
  implementation(files(protocolJars.map { "$jetbrainsClientLib/$it" }))
  // JetBrains Runtime's API (com.jetbrains.JBR): the macOS title bar (MacTitleBar.kt). The same jar
  // IntelliJ ships; it calls into the runtime and reports "not supported" on other Java runtimes.
  implementation(files("$jetbrainsClientLib/intellij.libraries.jbr.jar"))
  // JetBrains' Fleet RPC client and Rhizome database, from the same install (the kernel spike,
  // docs/feature-parity-plan.md section 5).
  implementation(
    files(
      listOf(
          "fleet.rpc.jar",
          "fleet.rhizomedb.jar",
          "fleet.rhizomedb.transactor.jar",
          "fleet.rhizomedb.transactor.rebase.jar",
          "fleet.util.core.jar",
          "fleet.util.serialization.jar",
          "fleet.util.logging.api.jar",
          "fleet.util.codepoints.jar",
          "fleet.multiplatform.shims.jar",
          "fleet.reporting.api.jar",
          "fleet.reporting.shared.jar",
          "fleet.bifurcan.jar",
          "fleet.fastutil.jar",
          "fleet.openmap.jar",
          "fleet.radixTrie.jar",
          "intellij.libraries.kotlinx.collections.immutable.jar",
        )
        .map { "$jetbrainsClientLib/$it" },
    ),
  )
  implementation(compose.desktop.currentOs)
  // Dispatchers.Main on the desktop (ConnectController); Compose Desktop doesn't bring it. It also
  // sets coroutines-core: 1.11, which JetBrains' Fleet jars are built against (1.9 lacks
  // SendChannel.close's default-argument bridge they call).
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
  // Pixel tests of the Lux painter need Skia, which only this module has.
  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

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
