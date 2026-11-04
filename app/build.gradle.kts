import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Release signing certificate fingerprint (CN=SpendSense, O=Jayant Nagori, C=IN). */
val RELEASE_CERT_SHA256 = "adf4b21eb88f680422f6679ba10adc87435a8a03bb3514c8110b5b95c79c9fc7"

android {
    namespace = "com.jayant.spendsense"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jayant.spendsense"
        minSdk = 26
        targetSdk = 35
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        // Runtime anti-tamper check (security/SignatureCheck.kt): empty = not checked (debug).
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
    }

    // Release key lives outside the repo (~/spendsense-keys/keystore.properties). Without it, release falls back
    // to the debug key so the project still builds on other machines.
    val keystoreProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/spendsense-keys/keystore.properties")
        if (f.exists()) f.inputStream().use { stream -> this.load(stream) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // SHA-256 of the release signing certificate (public value, not a secret), from
            // `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. A release APK signed
            // with any other key (repackaged, or built without ~/spendsense-keys) shows the "not genuine" screen.
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$RELEASE_CERT_SHA256\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    // Connected apps: OAuth sign-in in a Custom Tab
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
    // Statement import: PDF text extraction (incl. password-protected statements)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Email import: IMAP (Gmail with an App Password)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
