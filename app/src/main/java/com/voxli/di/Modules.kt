package com.voxli.di

import android.app.Application
import com.voxli.catalog.db.VoxliDatabase
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.knigavuhe.matcher.KnigavuheMatcher
import com.voxli.network.NetworkModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

val appModule = module {
    single { NetworkModule.provideOkHttpClient() }
    single { NetworkModule.provideDoHClient() }
    single { VoxliDatabase.create(get()) }
    single { get<VoxliDatabase>().bookDao() }
    single { get<VoxliDatabase>().historyDao() }
    single { get<VoxliDatabase>().settingsDao() }
    single { FlibustaProvider(get()) }
    single { KnigavuheMatcher(get()) }
}

fun initKoin(app: Application) {
    startKoin {
        androidContext(app)
        modules(appModule)
    }
}
