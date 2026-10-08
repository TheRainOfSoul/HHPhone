import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

// Постоянный ключ подписи HHPhone вне репозитория: ~/.hhphone/keystore.properties + android-release.jks.
// Терять нельзя: APK с другим ключом не встанет поверх установленного.
val hhReleaseKeys = Properties().apply {
    val f = File(System.getProperty("user.home"), ".hhphone/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("com.android.application")
}

configure<ApplicationExtension> {
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "com.tutpro.baresip.plus"
        minSdk = 28
        targetSdk = 36
        versionCode = 319
        versionName = "82.1.0"
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                cFlags += "-DHAVE_INTTYPES_H -lstdc++"
                arguments.addAll(listOf("-DANDROID_STL=c++_shared", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"))
            }
        }
        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
        vectorDrawables.useSupportLibrary = true
    }
    flavorDimensions += "brand"
    productFlavors {
        create("hh") {
            dimension = "brand"
            applicationId = "am.dgsolutions.hhphone"
            versionCode = 1
            versionName = "0.1.0"
            androidResources.localeFilters += listOf("en", "ru", "hy")
        }
    }
    signingConfigs {
        create("hhRelease") {
            if (!hhReleaseKeys.isEmpty) {
                storeFile = file(hhReleaseKeys.getProperty("storeFile"))
                storePassword = hhReleaseKeys.getProperty("password")
                keyAlias = hhReleaseKeys.getProperty("keyAlias")
                keyPassword = hhReleaseKeys.getProperty("password")
            }
        }
    }
    buildTypes {
        debug {
            ndk { abiFilters.add("x86_64") }
        }
        release {
            if (!hhReleaseKeys.isEmpty) signingConfig = signingConfigs.getByName("hhRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    namespace = "com.tutpro.baresip.plus"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}

dependencies {
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.compose.material3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.android)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.mmslib)
    testImplementation(libs.junit)
}

