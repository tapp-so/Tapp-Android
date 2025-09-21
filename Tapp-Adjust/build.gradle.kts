plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tapp.adjust"
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
}

dependencies {
    // Depend on the core module
    api(project(":Tapp-Core"))

    // Adjust SDK dependencies
    api("com.adjust.sdk:adjust-android:5.0.0") {
        exclude(group = "com.adjust.signature", module = "adjust-android-signature")
    }
    api("com.adjust.sdk:adjust-android-webbridge:5.0.0") {
        exclude(group = "com.adjust.signature", module = "adjust-android-signature")
    }
    api("com.adjust.signature:adjust-android-signature") {
        version {
            strictly("3.47.0")
        }
    }
    api("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}