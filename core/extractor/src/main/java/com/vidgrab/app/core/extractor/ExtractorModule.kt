package com.vidgrab.app.core.extractor

import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit

val extractorModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    single { NewPipeMediaExtractor(get(), Locale.getDefault().language.ifBlank { "en" }) }
    single<MediaExtractor> { get<NewPipeMediaExtractor>() }
    single { WebMediaFinder(get()) }
    single { HlsClient(get()) }
    single { StreamResolver(get(), get()) }
}
