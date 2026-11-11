plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

android {
    namespace = "com.mivr.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mivr.controller"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.2.3"
    }

    buildTypes.getByName("release") {
        // Keep the existing signing identity so the Redmi can update in place.
        signingConfig = signingConfigs.getByName("debug")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val redmiAssets = layout.buildDirectory.dir("generated/redmiAssets")
val injector = rootProject.file(".toolchain/frida-inject-17.26.0-android-arm64")
val prepareRedmiAssets by tasks.registering(Sync::class) {
    doFirst { check(injector.isFile) { "Missing Frida injector. See README.md." } }
    from(injector) {
        rename { "redmi-injector" }
    }
    from(rootProject.file("tools/redmi-static-random.js"))
    into(redmiAssets)
}

android.sourceSets.getByName("main").assets.srcDir(redmiAssets)
tasks.named("preBuild").configure { dependsOn(prepareRedmiAssets) }

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
