plugins {
    alias(libs.plugins.vidgrab.android.library.compose)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    api(projects.core.media)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
}
