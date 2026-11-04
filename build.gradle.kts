plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}

// Single source of truth for the app version: Android versionName/versionCode, the shared User-Agent and the iOS bundle.
extra["appVersionName"] = "1.3.1"
extra["appVersionCode"] = 5
