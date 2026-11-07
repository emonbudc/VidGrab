package com.vidgrab.app.core.data

import com.vidgrab.app.core.database.databaseModule
import com.vidgrab.app.core.datastore.datastoreModule
import com.vidgrab.app.core.extractor.extractorModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    includes(databaseModule, datastoreModule, extractorModule)
    single<MediaRepository> { MediaRepositoryImpl(get()) }
    single<LibraryRepository> { LibraryRepositoryImpl(androidContext(), get(), get(), get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
    single<BrowserRepository> { BrowserRepositoryImpl(get(), get(), get()) }
    single<SearchHistoryRepository> { SearchHistoryRepositoryImpl(get()) }
    single<GalleryRepository> { MediaStoreGalleryRepository(androidContext()) }
    single<PlaybackHistoryRepository> { PlaybackHistoryRepositoryImpl(get()) }
    single<SavedVideosRepository> { SavedVideosRepositoryImpl(get()) }
}
