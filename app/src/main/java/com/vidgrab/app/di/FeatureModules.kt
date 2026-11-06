package com.vidgrab.app.di

import com.vidgrab.app.feature.browser.browserModule
import com.vidgrab.app.feature.gallery.galleryModule
import com.vidgrab.app.feature.home.homeModule
import com.vidgrab.app.feature.library.libraryModule
import com.vidgrab.app.feature.search.searchModule
import com.vidgrab.app.feature.settings.settingsModule
import com.vidgrab.app.feature.tools.toolsModule
import com.vidgrab.app.feature.video.videoModule

/** Add a feature's Koin module here when adding a new feature module. */
val featureModules = listOf(appModule, homeModule, searchModule, videoModule, libraryModule,
    browserModule, settingsModule, toolsModule, galleryModule,
)
