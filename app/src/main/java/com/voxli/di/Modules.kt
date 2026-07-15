package com.voxli.di

import android.app.Application
import com.voxli.catalog.db.VoxliDatabase
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.knigavuhe.matcher.KnigavuheMatcher
import com.voxli.network.NetworkModule
import com.voxli.reader.engine.BookDownloader
import com.voxli.settings.SettingsRepository
import com.voxli.ui.reader.ReaderViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    // Network
    single<OkHttpClient> { NetworkModule.provideOkHttpClient(get().cacheDir) }
    single { NetworkModule.provideDoHClient(get().cacheDir) }

    // Database
    single { VoxliDatabase.create(get()) }
    single { get<VoxliDatabase>().bookDao() }
    single { get<VoxliDatabase>().historyDao() }
    single { get<VoxliDatabase>().settingsDao() }

    // Repositories
    single { SettingsRepository(get()) }
    single { BookDownloader(get(), get()) }

    // Providers
    single { FlibustaProvider(get()) }
    single { KnigavuheMatcher(get()) }

    // ViewModels
    viewModel { ReaderViewModel(get(), get(), get(), get()) }
}

fun initKoin(app: Application) {
    startKoin {
        androidContext(app)
        modules(appModule)
    }
}
