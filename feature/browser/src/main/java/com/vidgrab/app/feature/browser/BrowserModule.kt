package com.vidgrab.app.feature.browser

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val browserModule = module {
    viewModel { BrowserViewModel(get(), get()) }
    viewModel { SitesViewModel(get()) }
}
