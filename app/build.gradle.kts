import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val rpcUrl: String =
    providers
        .gradleProperty("rpcUrl")
        .getOrElse("https://ethereum-sepolia-rpc.publicnode.com")

android {
    namespace = "io.github.vladbokov.wallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.vladbokov.wallet"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "RPC_URL", "\"$rpcUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:rpc"))
    implementation(project(":core:wallet"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Unlike the JVM plugin, AGP does not pick the JUnit 5 flavour of kotlin-test by itself.
    testImplementation(libs.kotlin.test.junit5)
}
