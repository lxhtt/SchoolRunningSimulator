import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) FileInputStream(file).use(::load)
}
val amapApiKey = providers.environmentVariable("AMAP_API_KEY").orElse(localProperties.getProperty("AMAP_API_KEY", ""))
val signingStoreFile = providers.environmentVariable("RATEMOCK_KEYSTORE_PATH").orElse(localProperties.getProperty("RATEMOCK_KEYSTORE_PATH", "")).get()
val signingStorePassword = providers.environmentVariable("RATEMOCK_KEYSTORE_PASSWORD").orElse(localProperties.getProperty("RATEMOCK_KEYSTORE_PASSWORD", "")).get()
val signingKeyAlias = providers.environmentVariable("RATEMOCK_KEY_ALIAS").orElse(localProperties.getProperty("RATEMOCK_KEY_ALIAS", "")).get()
val signingKeyPassword = providers.environmentVariable("RATEMOCK_KEY_PASSWORD").orElse(localProperties.getProperty("RATEMOCK_KEY_PASSWORD", "")).get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ratemock.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.ratemock.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-p0"
        manifestPlaceholders["amapApiKey"] = amapApiKey.get()
    }

    signingConfigs {
        if (signingStoreFile.isNotBlank() && signingStorePassword.isNotBlank() && signingKeyAlias.isNotBlank() && signingKeyPassword.isNotBlank()) {
            create("ratemockFixedDebug") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (signingStoreFile.isNotBlank() && signingStorePassword.isNotBlank() && signingKeyAlias.isNotBlank() && signingKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("ratemockFixedDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":receiver-ui"))
    // Substituted with the independent local build by settings.includeBuild.
    implementation("dev.ratemock:sim-core:0.1.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.amap3dmap)
    implementation(libs.amap.location)
    implementation(libs.amap.search)
    implementation(libs.androidx.compose.material3)
}