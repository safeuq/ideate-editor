// Ideate's own Gradle plugins, included by the root settings.gradle.kts (pluginManagement).
plugins {
  `kotlin-dsl`
  id("com.diffplug.spotless") version "8.11.0"
}

repositories { mavenCentral() }

gradlePlugin {
  plugins {
    register("jetbrainsClient") {
      id = "io.github.safeuq.ideate.jetbrains-client"
      implementationClass = "io.github.safeuq.ideate.gradle.JetBrainsClientPlugin"
    }
  }
}

spotless {
  kotlin { ktfmt().googleStyle() }
  kotlinGradle { ktfmt().googleStyle() }
}
