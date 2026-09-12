// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.8.0"
}

apply(from = "gradle/develocity.settings.gradle")

rootProject.name = "kzstd"
