plugins {
    alias(libs.plugins.vidgrab.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.extractor)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
}
