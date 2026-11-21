import java.util.Properties

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.hot.reload) apply false
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
      // The terminal emulator (docs/terminal-and-consoles-spec.md section 4); no UI classes. Its
      // only AWT use is TtyConnector.resize(java.awt.Dimension), which nothing here calls.
      "intellij.libraries.jediterm.core.jar",
      "intellij.platform.split.protocol.jar",
      "intellij.rd.ide.model.generated.jar",
    ),
  )

/**
 * JetBrains' Fleet RPC client and Rhizome database (the kernel spike, docs/feature-parity-plan.md
 * section 5), file names in [jetbrainsClientLib], used like [protocolJars].
 */
val fleetJars by
  extra(
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
