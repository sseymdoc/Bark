plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// google-services.json comes from your own Firebase project (see README); without it the app builds but cannot receive pushes.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "me.fin.bark"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.fin.bark"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Default server for a fresh install; api.day.app only serves iOS, so point this at your own bark-server with FCM enabled.
        buildConfigField("String", "DEFAULT_SERVER", "\"${project.findProperty("bark.defaultServer") ?: "https://api.day.app"}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar stubs org.json in JVM unit tests
    testImplementation("org.json:json:20260522")
}

// BeszelTest reads the live Hub when BARK_MONITOR_URL is set. Gradle does not track environment variables,
// so without this a run with it set right after one without would be skipped as up to date.
tasks.withType<Test>().configureEach { outputs.upToDateWhen { System.getenv("BARK_MONITOR_URL") == null } }
