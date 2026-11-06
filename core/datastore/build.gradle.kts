plugins {
    alias(libs.plugins.vidgrab.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.datastore.preferences)
}
