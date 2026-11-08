package com.vidgrab.app.feature.home

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val homeModule = module {
    viewModel { HomeViewModel(get(), get(), get(), get(), get()) }
}
