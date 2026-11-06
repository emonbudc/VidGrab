import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            // Namespace derived from the module path, e.g. :core:data -> com.vidgrab.app.core.data
            namespace = "com.vidgrab.app" + path.replace(':', '.').replace('-', '_')
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
        dependencies {
            add("implementation", platform(libs.lib("koin-bom")))
            add("implementation", libs.lib("koin-android"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("testImplementation", libs.lib("junit"))
        }
    }
}
