import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("maven-publish")
  id("signing")
}

group = "me.golocalise"
version = "1.0.0"

android {
  namespace = "com.golocalise.sdk"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
      withJavadocJar()
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

publishing {
  publications {
    register<MavenPublication>("release") {
      groupId = "me.golocalise"
      artifactId = "golocalise-android"
      version = "1.0.0"

      afterEvaluate {
        from(components["release"])
      }

      pom {
        name.set("GoLocalise Android SDK")
        description.set(
          "Official GoLocalise Android SDK for OTA localization."
        )
        url.set("https://golocalise.me")

        licenses {
          license {
            name.set("Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }

        developers {
          developer {
            id.set("abdallahnh")
            name.set("Abdallah Nehme")
            email.set("sales@golocalise.me")
          }
        }

        scm {
          connection.set(
            "scm:git:git://github.com/abdallahnh/golocalise-android.git"
          )
          developerConnection.set(
            "scm:git:ssh://git@github.com/abdallahnh/golocalise-android.git"
          )
          url.set("https://github.com/abdallahnh/golocalise-android")
        }
      }
    }
  }

  repositories {
    maven {
      name = "localRelease"
      url = uri(layout.buildDirectory.dir("maven-repository"))
    }
  }
}

signing {
  useGpgCmd()
  sign(publishing.publications["release"])
}

tasks.withType<Sign>().configureEach {
  onlyIf { gradle.taskGraph.allTasks.any { task -> task.name.contains("Publication") } }
}
