plugins {
    alias(libs.plugins.vidgrab.android.feature)
}

dependencies {
    implementation(projects.core.download)
    implementation(projects.core.extractor)
    implementation(projects.core.playerui)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
}
