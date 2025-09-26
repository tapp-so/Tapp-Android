plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Define the publishing configuration for JitPack
group = "com.github.tapp-so"
version = "1.0.0"

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "Tapp-Appsflyer"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

android {
    namespace = "com.tapp.appsflyer"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}

dependencies {
    // Depend on the core module
    api(project(":Tapp-Core"))

    // AppsFlyer SDK dependency (placeholder)
    api("com.appsflyer:af-android-sdk:6.12.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}