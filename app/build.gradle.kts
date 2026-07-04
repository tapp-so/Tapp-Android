import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""

val sampleEnvironment = localProperties.getProperty("TAPP_SAMPLE_ENV", "SANDBOX")
val sampleAuthToken = localProperties.getProperty("TAPP_SAMPLE_AUTH_TOKEN", "")
val sampleTappToken = localProperties.getProperty("TAPP_SAMPLE_TAPP_TOKEN", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.tapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tapp"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TAPP_SAMPLE_ENV", buildConfigString(sampleEnvironment))
        buildConfigField("String", "TAPP_SAMPLE_AUTH_TOKEN", buildConfigString(sampleAuthToken))
        buildConfigField("String", "TAPP_SAMPLE_TAPP_TOKEN", buildConfigString(sampleTappToken))
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "provider"
    productFlavors {
        create("native") {
            dimension = "provider"
            buildConfigField("String", "SAMPLE_PROVIDER", "\"TAPP_NATIVE\"")
            buildConfigField("String", "SAMPLE_PROVIDER_LABEL", "\"Tapp Native\"")
        }
        create("adjust") {
            dimension = "provider"
            buildConfigField("String", "SAMPLE_PROVIDER", "\"ADJUST\"")
            buildConfigField("String", "SAMPLE_PROVIDER_LABEL", "\"Adjust\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    add("nativeImplementation", project(":Tapp-Native"))
    add("adjustImplementation", project(":Tapp-Adjust"))
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
