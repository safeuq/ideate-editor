import java.util.Properties

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform) apply false
  alias(libs.plugins.spotless)
}

// The rd library and the generated protocol models are taken from JetBrains Client itself. They
// must be
// the same build as the backend (263.7307.200): both sides compare a schema hash per model.
// Example:
// ~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib
// Looked up in this order: -Pjetbrains.client.lib, local.properties (not committed, also holds
// sdk.dir; the IDE's
// Gradle sync sees it, unlike shell variables), JETBRAINS_CLIENT_LIB.
val localProperties =
  Properties().apply {
    val localFile = file("local.properties")
    if (localFile.isFile) localFile.inputStream().use(::load)
  }
val jetbrainsClientLib by
  extra(
    providers.gradleProperty("jetbrains.client.lib").orNull
      ?: localProperties.getProperty("jetbrains.client.lib")
      ?: providers.environmentVariable("JETBRAINS_CLIENT_LIB").orNull
      ?: error(
        "Set jetbrains.client.lib in local.properties (or JETBRAINS_CLIENT_LIB, or -Pjetbrains.client.lib=…) to JetBrains Client 263.7307.200's Contents/lib folder",
      ),
  )

/**
 * File names in [jetbrainsClientLib]. compose compiles against them; each app adds them at run time
 * (android: patched for ART).
 */
val protocolJars by
  extra(
    listOf(
      "intellij.libraries.rd.core.jar",
      "intellij.libraries.rd.framework.jar",
      "intellij.libraries.rd.text.jar",
      "intellij.platform.split.protocol.jar",
      "intellij.rd.ide.model.generated.jar",
    ),
  )

subprojects {
  apply(plugin = "com.diffplug.spotless")

  spotless {
    kotlin {
      target("src/**/*.kt")
      ktfmt().googleStyle()
    }
  }
}

allprojects {
  spotless {
    format("misc") {
      target("*.md")
      trimTrailingWhitespace()
      endWithNewline()
    }
    kotlinGradle { ktfmt().googleStyle() }
  }
}
