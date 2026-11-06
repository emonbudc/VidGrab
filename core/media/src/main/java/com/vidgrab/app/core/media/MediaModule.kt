package com.vidgrab.app.core.media

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val mediaModule = module {
    single { PlayerController(androidContext(), get(), get()) }
}
