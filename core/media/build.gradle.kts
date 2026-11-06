plugins {
    alias(libs.plugins.vidgrab.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    api(libs.libvlc.all)
    api(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
}
