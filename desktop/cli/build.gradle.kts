import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
    // Phase 1 of the desktop port: compile the portable core sources directly on the JVM.
    // Android-only files are replaced or excluded until the modules become multiplatform.
    sourceSets["main"].kotlin.srcDir(rootProject.file("core/model/src/main/java"))
    sourceSets["main"].kotlin.srcDir(rootProject.file("core/common/src/main/java"))
    sourceSets["main"].kotlin.srcDir(rootProject.file("core/extractor/src/main/java"))
    sourceSets["main"].kotlin.exclude(
        "**/DeviceCodecs.kt",
        "**/Permissions.kt",
        "**/ExtractorModule.kt",
    )
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.org.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.vidgrab.app.desktop.cli.MainKt")
    applicationName = "vidgrab-cli"
}
