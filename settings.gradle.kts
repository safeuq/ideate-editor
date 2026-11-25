rootProject.name = "ideate-editor"

pluginManagement {
  // Ideate's own Gradle plugins (ideate-gradle/).
  includeBuild("ideate-gradle")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

// Sets jetbrainsClientLib for every project; downloads JetBrains Client when no folder is named.
plugins { id("io.github.safeuq.ideate.jetbrains-client") }

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

// compose: connection, session and UI, shared by both apps. desktop: the JVM launcher. android: the
// Android app.
include("compose", "desktop", "android")
