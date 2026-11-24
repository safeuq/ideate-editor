import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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

// The macOS disk image (docs/release-dmg.md): `./gradlew :desktop:packageDmg` writes
// build/dmg/Ideate-<version>.dmg. Compose's own packageDmg cannot be used: it builds the runtime
// with jlink, and the JetBrains Runtime the app needs has no jlink and no jmods.
// jpackage refuses a version that starts with 0 on macOS, so the first release is 1.0.0.
val dmgVersion = providers.gradleProperty("ideateVersion").orElse("1.0.0")
val dmgFolder = layout.buildDirectory.dir("dmg")
val clientRoot = file(jetbrainsClientLib).parentFile

// jpackage comes with a full JDK, e.g. ~/.sdkman/candidates/java/25.0.5.0+8-tem/bin/jpackage;
// -Pjpackage=<path> names it when the Java 25 that Gradle finds is a JetBrains Runtime.
val jpackagePath =
  providers
    .gradleProperty("jpackage")
    .orElse(
      javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .map { it.metadata.installationPath.file("bin/jpackage").asFile.path },
    )

fun runCommand(vararg command: String) {
  val process = ProcessBuilder(*command).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText()
  check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
}

// Every SVG of the client's jars in one jar, as the Android app packs them (ClientAssets in
// android/build.gradle.kts): DesktopResources.JarIcons looks through every jar under
// jetbrains.client.lib, and the client's own lib and plugins folders are 2.5 GB.
val dmgIconsJar by tasks.registering {
  val iconJars =
    files("$jetbrainsClientLib/intellij.platform.icons.jar") +
      fileTree(clientRoot) { include("lib/**/*.jar", "plugins/**/*.jar") }
  val output = dmgFolder.map { it.file("icons/icons.jar") }
  inputs.files(iconJars)
  outputs.file(output)
  doLast {
    val written = mutableSetOf<String>()
    ZipOutputStream(output.get().asFile.outputStream()).use { icons ->
      iconJars.forEach { jar ->
        ZipFile(jar).use { zip ->
          zip
            .entries()
            .asSequence()
            .filter { it.name.endsWith(".svg") && written.add(it.name) }
            .forEach { entry ->
              icons.putNextEntry(ZipEntry(entry.name))
              zip.getInputStream(entry).use { it.copyTo(icons) }
              icons.closeEntry()
            }
        }
      }
    }
  }
}

// Becomes Ideate.app/Contents/app. `client/` has the same shape as a JetBrains Client install, so
// Main.kt reads fonts and icons from it through jetbrains.client.lib as it does in a Gradle run.
val dmgInput by
  tasks.registering(Sync::class) {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(dmgFolder.map { it.dir("input") })
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from(files("$jetbrainsClientLib/intellij.platform.icons.jar", dmgIconsJar)) {
      into("client/lib")
    }
    from(
      listOf(
          "JetBrainsMono-Regular.ttf",
          "JetBrainsMono-Bold.ttf",
          "JetBrainsMono-Italic.ttf",
          "JetBrainsMono-BoldItalic.ttf",
          "Inter-Regular.otf",
          "Inter-SemiBold.otf",
        )
        .map { clientRoot.resolve("jbr/Contents/Home/lib/fonts/$it") },
    ) {
      into("client/jbr/Contents/Home/lib/fonts")
    }
  }

val dmgAppImage by tasks.registering {
  description = "Builds build/dmg/image/Ideate.app with the client's JetBrains Runtime inside"
  dependsOn(dmgInput)
  val image = dmgFolder.map { it.dir("image") }
  val icon = rootProject.file("app-icon/dark/macos/Ideate.icns")
  inputs.files(dmgInput, icon)
  inputs.property("version", dmgVersion)
  outputs.dir(image)
  doLast {
    check(isMac) { "The DMG is built on macOS only" }
    val jpackage = jpackagePath.get()
    check(file(jpackage).isFile) {
      "No jpackage at $jpackage; pass -Pjpackage=<a full JDK 25>/bin/jpackage"
    }
    val runtime = clientRoot.resolve("jbr")
    check(runtime.resolve("Contents/Home/bin/java").isFile) { "No JetBrains Runtime at $runtime" }
    check(icon.isFile) { "No icon at $icon" }
    // Gradle's delete removes links without following them (the runtime copy has some).
    delete(image)
    runCommand(
      jpackage,
      "--type",
      "app-image",
      "--name",
      "Ideate",
      "--app-version",
      dmgVersion.get(),
      "--mac-package-identifier",
      "io.github.safeuq.ideate",
      "--icon",
      icon.path,
      "--input",
      dmgFolder.get().dir("input").asFile.path,
      "--main-jar",
      tasks.jar.get().archiveFileName.get(),
      "--main-class",
      compose.desktop.application.mainClass!!,
      "--runtime-image",
      runtime.path,
      "--java-options",
      "-Djetbrains.client.lib=\$APPDIR/client/lib",
      // What Compose's run task sets: Swing follows the system's text and menu settings.
      "--java-options",
      "-Dcompose.application.configure.swing.globals=true",
      "--dest",
      image.get().asFile.path,
    )
    // The title bar's layout follows the SDK the launcher was built with (see prepareSdk26Jbr).
    val app = image.get().dir("Ideate.app").asFile
    val launcher = app.resolve("Contents/MacOS/Ideate").path
    runCommand(
      "xcrun",
      "vtool",
      "-set-build-version",
      "macos",
      "11.0",
      "26.0",
      "-replace",
      "-output",
      launcher,
      launcher,
    )
    runCommand("codesign", "--force", "--deep", "--sign", "-", app.path)
  }
}

val packageDmg by tasks.registering {
  description = "Builds build/dmg/Ideate-<version>.dmg (docs/release-dmg.md)"
  dependsOn(dmgAppImage)
  val dmg = dmgFolder.zip(dmgVersion) { folder, version -> folder.file("Ideate-$version.dmg") }
  inputs.files(dmgAppImage)
  outputs.file(dmg)
  doLast {
    delete(dmg)
    runCommand(
      jpackagePath.get(),
      "--type",
      "dmg",
      "--name",
      "Ideate",
      "--app-version",
      dmgVersion.get(),
      "--app-image",
      dmgFolder.get().dir("image/Ideate.app").asFile.path,
      "--dest",
      dmgFolder.get().asFile.path,
    )
  }
}
