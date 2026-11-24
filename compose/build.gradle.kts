import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  kotlin("plugin.serialization")
  id("com.android.kotlin.multiplatform.library")
}

val jetbrainsClientLib: String by rootProject.extra
val protocolJars: List<String> by rootProject.extra
val fleetJars: List<String> by rootProject.extra

val protocolJarFiles = protocolJars.map { name ->
  file("$jetbrainsClientLib/$name").also { jar ->
    check(jar.isFile) {
      "Missing $jar: is jetbrains.client.lib (local.properties) the client's Contents/lib folder?"
    }
  }
}

kotlin {
  jvm {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
  }
  android {
    namespace = "io.github.safeuq.ideate.client.compose"
    compileSdk = 37
    // java.util.HexFormat (client certificate fingerprints) needs 34.
    minSdk = 34
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
  }

  sourceSets {
    commonMain.dependencies {
      compileOnly(files(protocolJarFiles))
      compileOnly(files(fleetJars.map { "$jetbrainsClientLib/$it" }))
      // 1.84: the version SSHJ 0.41.2 is built against (it brings bcprov 1.84 itself).
      implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
      // SSH connections (docs/ssh-connections.md): the library JetBrains Gateway uses.
      implementation("com.hierynomus:sshj:0.41.2")
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
      // The HTML parser android.text.Html uses (HtmlText.kt ports its converter).
      implementation("org.ccil.cowan.tagsoup:tagsoup:1.2.1")
      implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    }
    jvmTest.dependencies {
      implementation(files(protocolJarFiles))
      implementation(kotlin("test"))
    }
  }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
