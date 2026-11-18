import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.vladbokov.wallet.core.wallet"
    compileSdk = 37

    defaultConfig {
        // 30+ gives us KeyGenParameterSpec.setUserAuthenticationParameters (per-use biometric auth).
        minSdk = 30
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
    // web3j stays an implementation detail of this module: the app never touches its types.
    implementation(libs.web3j.crypto)
    implementation(libs.web3j.utils)
    implementation(libs.web3j.rlp)
    implementation(libs.web3j.abi)
    implementation(libs.bouncycastle.bcprov)

    // Unlike the JVM plugin, AGP does not pick the JUnit 5 flavour of kotlin-test by itself.
    testImplementation(libs.kotlin.test.junit5)
}
