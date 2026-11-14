plugins { id("com.android.application") }
android {
    namespace = "com.tvnetsolutions.jr"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tvnetsolutions.jrplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "ClickTV.013"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/clicktv.keystore")
            storePassword = "android"
            keyAlias = "clicktvkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
}
