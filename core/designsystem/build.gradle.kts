plugins {
    alias(libs.plugins.vidgrab.android.library.compose)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    api(projects.core.model)
    implementation(libs.androidx.navigation.compose)
}
