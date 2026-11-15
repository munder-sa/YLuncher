import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "com.gt8d.launcher.carback"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gt8d.launcher.carback"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val androidAllDir = File(
                System.getProperty("user.home"),
                ".m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7",
            )
            if (androidAllDir.isDirectory) {
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", androidAllDir.path)
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
