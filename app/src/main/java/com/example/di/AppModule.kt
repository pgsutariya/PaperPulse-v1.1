package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.feed.live.createLiveWebSocketOkHttpClient
import com.example.data.local.AccountDao
import com.example.data.local.InstrumentDao
import com.example.data.local.OcoLinkDao
import com.example.data.local.OrderDao
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.PositionDao
import com.example.data.local.TradeJournalDao
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.MarketDataRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("liveWs")
    fun provideLiveWsOkHttpClient(): OkHttpClient {
        return createLiveWebSocketOkHttpClient()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PaperPulseDatabase {
        return Room.databaseBuilder(
            context,
            PaperPulseDatabase::class.java,
            "paperpulse.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides fun provideInstrumentDao(db: PaperPulseDatabase): InstrumentDao = db.instrumentDao()
    @Provides fun provideOrderDao(db: PaperPulseDatabase): OrderDao = db.orderDao()
    @Provides fun providePositionDao(db: PaperPulseDatabase): PositionDao = db.positionDao()
    @Provides fun provideOcoLinkDao(db: PaperPulseDatabase): OcoLinkDao = db.ocoLinkDao()
    @Provides fun provideTradeJournalDao(db: PaperPulseDatabase): TradeJournalDao = db.tradeJournalDao()
    @Provides fun provideCapitalTransactionDao(db: PaperPulseDatabase): com.example.data.local.CapitalTransactionDao = db.capitalTransactionDao()
    @Provides fun provideAccountDao(db: PaperPulseDatabase): AccountDao = db.accountDao()
    @Provides fun provideCandleDao(db: PaperPulseDatabase): com.example.data.local.CandleDao = db.candleDao()
    @Provides fun provideSignalDao(db: PaperPulseDatabase): com.example.data.local.SignalDao = db.signalDao()
    @Provides fun provideSavedSignalDao(db: PaperPulseDatabase): com.example.data.local.SavedSignalDao = db.savedSignalDao()
    @Provides fun provideSignalRecordDao(db: PaperPulseDatabase): com.example.data.local.SignalRecordDao = db.signalRecordDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingModule {
    @Binds
    @Singleton
    abstract fun bindMarketDataRepository(
        impl: MarketDataRepositoryImpl
    ): MarketDataRepository

    @Binds
    @Singleton
    abstract fun bindLiveFeedFactory(
        impl: com.example.data.feed.live.DefaultLiveFeedFactory
    ): com.example.data.feed.live.LiveFeedFactory

    @Binds
    @Singleton
    abstract fun bindSignalEngine(
        impl: com.example.domain.signal.CompositeSignalEngine
    ): com.example.domain.signal.SignalEngine

    @Binds
    @Singleton
    abstract fun bindAiMarketRepository(
        impl: com.example.data.ai.FirebaseAiMarketRepository
    ): com.example.data.ai.AiMarketRepository
}
