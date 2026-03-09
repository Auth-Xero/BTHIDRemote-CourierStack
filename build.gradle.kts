buildscript {
    val agp_version by extra("8.5.0")
    val agp_version1 by extra("8.7.0")
}
// Top-level build file for Bluetooth HID Remote
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
