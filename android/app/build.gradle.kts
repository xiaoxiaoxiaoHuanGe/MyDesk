plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

if (file("google-services.json").exists()) apply(plugin="com.google.gms.google-services")

val releaseKeyStore = providers.environmentVariable("MYDESK_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("MYDESK_RELEASE_STORE_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("MYDESK_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MYDESK_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(releaseKeyStore,releaseStorePassword,releaseAlias,releaseKeyPassword).all { !it.isNullOrBlank() } && releaseKeyStore?.let {file(it).isFile} == true
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast { check(releaseSigningReady) { "SIGNING_NOT_CONFIGURED: run scripts/Build-AndroidRelease.ps1 with private release signing configured" } }
}
tasks.matching {it.name == "preReleaseBuild"}.configureEach {dependsOn(verifyReleaseSigning)}

tasks.withType<Test>().configureEach {
    doFirst { rootProject.file("../.local/build-home").mkdirs() }
    systemProperty("user.home", rootProject.file("../.local/build-home").absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
android {
    namespace = "app.mydesk.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.mydesk.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("../.local/android/debug.keystore")
    }
    if(releaseSigningReady) signingConfigs.create("release") {
        storeFile=file(releaseKeyStore!!)
        storePassword=releaseStorePassword
        keyAlias=releaseAlias
        keyPassword=releaseKeyPassword
    }
    buildTypes {
        release {
            isDebuggable=false
            isMinifyEnabled=true
            isShrinkResources=true
            if(releaseSigningReady) signingConfig=signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("FCM transitively requests legacy Fragment; Activity Result permission APIs need a compatible modern version")
        }
    }
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
