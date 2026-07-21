// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

apply(from = "gradle/build-cache.settings.gradle")

rootProject.name = "kzstd"
