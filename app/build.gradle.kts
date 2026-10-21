import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juanc.adbtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.juanc.adbtv"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    // Clave de release: keystore.properties (local, fuera de git) o variables de entorno (CI).
    // Sin ninguna de las dos, el release se firma con la clave debug para poder probarlo.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
    val releaseStore = secret("storeFile", "ADBTV_KEYSTORE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("storePassword", "ADBTV_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "ADBTV_KEY_ALIAS")
                keyPassword = secret("keyPassword", "ADBTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
