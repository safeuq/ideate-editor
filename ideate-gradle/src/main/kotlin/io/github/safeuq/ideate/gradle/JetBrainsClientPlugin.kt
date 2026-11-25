package io.github.safeuq.ideate.gradle

import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider
import org.gradle.process.ExecOperations

/**
 * Sets the root project's extra property `jetbrainsClientLib`: the `Contents/lib` folder of
 * JetBrains Client, from which the build takes the protocol jars, the JetBrains Runtime, the fonts
 * and the icons.
 *
 * A folder the developer names wins, looked up in this order: `-Pjetbrains.client.lib=…`,
 * `jetbrains.client.lib` in `local.properties`, the `JETBRAINS_CLIENT_LIB` variable. With none of
 * them, the client of build `jetbrains.client.build` (`gradle.properties`, e.g. `263.7307.200`) is
 * downloaded and unpacked, the way the IntelliJ Platform Gradle Plugin gets an IDE: an Ivy
 * repository over `https://download.jetbrains.com`, and an artifact transform that unpacks the
 * archive once into Gradle's cache.
 */
class JetBrainsClientPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    settings.dependencyResolutionManagement.repositories.ivy {
      name = "JetBrains IDE Installers"
      setUrl("https://download.jetbrains.com")
      // e.g. idea/code-with-me/JetBrainsClient-263.7307.200-aarch64.sit
      patternLayout { artifact("[organization]/[module]-[revision](-[classifier]).[ext]") }
      // The server has the archive only, no Ivy or POM file.
      metadataSources { artifact() }
      content { includeModule(INSTALLER_GROUP, INSTALLER_NAME) }
    }
    settings.gradle.rootProject {
      extensions.extraProperties["jetbrainsClientLib"] =
        configuredLib(settings) ?: downloadedLib(this, settings)
    }
  }

  private fun configuredLib(settings: Settings): String? {
    val localProperties =
      Properties().apply {
        val localFile = File(settings.rootDir, "local.properties")
        if (localFile.isFile) localFile.inputStream().use(::load)
      }
    return settings.providers.gradleProperty("jetbrains.client.lib").orNull
      ?: localProperties.getProperty("jetbrains.client.lib")
      ?: settings.providers.environmentVariable("JETBRAINS_CLIENT_LIB").orNull
  }

  private fun downloadedLib(project: Project, settings: Settings): String {
    val build =
      settings.providers.gradleProperty("jetbrains.client.build").orNull
        ?: error("Set jetbrains.client.build in gradle.properties, e.g. 263.7307.200")
    val osName = System.getProperty("os.name")
    // The build reads the client in its macOS layout (`Contents/lib`, the runtime's fonts in
    // `Contents/jbr/Contents/Home/lib/fonts`), so only the macOS archive is downloaded for now.
    check(osName.startsWith("Mac")) {
      "JetBrains Client is downloaded on macOS only; on $osName set jetbrains.client.lib " +
        "(docs/client.md, \"Run\")"
    }
    val notation =
      mutableMapOf(
        "group" to INSTALLER_GROUP,
        "name" to INSTALLER_NAME,
        "version" to build,
        "ext" to ARCHIVE_TYPE,
      )
    // Apple silicon: JetBrainsClient-263.7307.200-aarch64.sit; Intel:
    // JetBrainsClient-263.7307.200.sit.
    if (System.getProperty("os.arch") == "aarch64") notation["classifier"] = "aarch64"

    project.dependencies.registerTransform(ExtractJetBrainsClient::class.java) {
      from.attribute(ARTIFACT_TYPE_ATTRIBUTE, ARCHIVE_TYPE)
      to.attribute(ARTIFACT_TYPE_ATTRIBUTE, EXTRACTED_TYPE)
    }
    val archive =
      project.configurations.detachedConfiguration(project.dependencies.create(notation))
    val extracted =
      archive.incoming
        .artifactView { attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, EXTRACTED_TYPE) }
        .files
        .singleFile
    val lib = extracted.resolve("IntelliJ IDEA.app/Contents/lib")
    check(lib.resolve("intellij.platform.split.protocol.jar").isFile) {
      "The JetBrains Client archive of build $build has no protocol jars in $lib"
    }
    return lib.path
  }

  private companion object {
    const val INSTALLER_GROUP = "idea/code-with-me"
    const val INSTALLER_NAME = "JetBrainsClient"
    const val ARCHIVE_TYPE = "sit"
    const val EXTRACTED_TYPE = "jetbrains-client-directory"
  }
}

/**
 * Unpacks a JetBrains Client `.sit` (a zip archive) into Gradle's transform cache. `unzip` and not
 * Gradle's `zipTree`: the app bundle has symbolic links, which `zipTree` writes as plain files, and
 * the runtime inside does not start without them.
 */
abstract class ExtractJetBrainsClient : TransformAction<TransformParameters.None> {
  @get:InputArtifact abstract val archive: Provider<FileSystemLocation>

  @get:Inject abstract val execOperations: ExecOperations

  override fun transform(outputs: TransformOutputs) {
    val target = outputs.dir("jetbrains-client")
    execOperations.exec {
      commandLine("/usr/bin/unzip", "-q", archive.get().asFile.path, "-d", target.path)
    }
  }
}
