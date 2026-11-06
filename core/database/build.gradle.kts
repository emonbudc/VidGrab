plugins {
    alias(libs.plugins.vidgrab.android.library)
    alias(libs.plugins.vidgrab.android.room)
}

dependencies {
    api(projects.core.model)
}
