plugins {
    alias(libs.plugins.vidgrab.android.library.compose)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(libs.coil.compose)
}
