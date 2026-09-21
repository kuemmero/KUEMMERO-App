plugins {
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

android {
    namespace = "de.kuemmero.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.kuemmero.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "1.2"
    }

    signingConfigs {
        create("kuemmeroFixed") {
            val storeFilePath = project.findProperty("kuemmeroStoreFile") as String?
            val storePasswordValue = project.findProperty("kuemmeroStorePassword") as String?
            val keyAliasValue = project.findProperty("kuemmeroKeyAlias") as String?
            val keyPasswordValue = project.findProperty("kuemmeroKeyPassword") as String?

            if (storeFilePath != null && storePasswordValue != null &&
                keyAliasValue != null && keyPasswordValue != null) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("kuemmeroFixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kuemmeroFixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
