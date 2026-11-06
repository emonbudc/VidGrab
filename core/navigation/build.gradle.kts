plugins {
    alias(libs.plugins.vidgrab.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)
}
