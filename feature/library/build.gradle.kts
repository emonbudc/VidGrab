plugins {
    alias(libs.plugins.vidgrab.android.feature)
}

dependencies {
    implementation(projects.core.download)
    implementation(projects.core.media)
    implementation(libs.coil.video)
}
