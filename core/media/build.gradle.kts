plugins {
    alias(libs.plugins.vidgrab.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.exoplayer.dash)
    api(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.exoplayer.rtsp)
    api(libs.androidx.media3.ui)
    api(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
}
