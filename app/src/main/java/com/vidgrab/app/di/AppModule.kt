package com.vidgrab.app.di

import com.vidgrab.app.navigation.UrlRouter
import com.vidgrab.app.BuildConfig
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single(named("updateUrl")) { BuildConfig.UPDATE_API_URL }
    single { UrlRouter(get(), get()) }
}
