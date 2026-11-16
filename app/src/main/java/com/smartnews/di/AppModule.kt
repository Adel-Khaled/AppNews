package com.smartnews.di

import android.content.Context
import androidx.room.Room
import com.smartnews.BuildConfig
import com.smartnews.data.local.ArticleDao
import com.smartnews.data.local.CategoryDao
import com.smartnews.data.local.HistoryDao
import com.smartnews.data.local.MediaDao
import com.smartnews.data.local.SmartNewsDatabase
import com.smartnews.data.local.SourceDao
import com.smartnews.data.local.StoryDao
import com.smartnews.data.remote.BackendApi
import com.smartnews.data.remote.BackendConfig
import com.smartnews.data.remote.BackendUrlInterceptor
import com.smartnews.data.repository.AiRepositoryImpl
import com.smartnews.data.repository.CategoryRepositoryImpl
import com.smartnews.data.repository.NewsRepositoryImpl
import com.smartnews.data.repository.SettingsRepositoryImpl
import com.smartnews.data.repository.SourceRepositoryImpl
import com.smartnews.domain.repository.AiRepository
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.domain.repository.SourceRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SmartNewsDatabase =
        Room.databaseBuilder(context, SmartNewsDatabase::class.java, "smartnews.db")
            .addMigrations(SmartNewsDatabase.MIGRATION_1_2)
            .build()

    @Provides fun categoryDao(db: SmartNewsDatabase): CategoryDao = db.categoryDao()
    @Provides fun sourceDao(db: SmartNewsDatabase): SourceDao = db.sourceDao()
    @Provides fun articleDao(db: SmartNewsDatabase): ArticleDao = db.articleDao()
    @Provides fun storyDao(db: SmartNewsDatabase): StoryDao = db.storyDao()
    @Provides fun mediaDao(db: SmartNewsDatabase): MediaDao = db.mediaDao()
    @Provides fun historyDao(db: SmartNewsDatabase): HistoryDao = db.historyDao()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context, backend: BackendConfig): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(BackendUrlInterceptor(backend))
            .apply {
                if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
            .build()

    @Provides @Singleton
    fun backendApi(client: OkHttpClient, json: Json): BackendApi =
        Retrofit.Builder()
            .baseUrl("https://${BackendConfig.PLACEHOLDER_HOST}/")
            .client(client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BackendApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun categories(impl: CategoryRepositoryImpl): CategoryRepository
    @Binds abstract fun sources(impl: SourceRepositoryImpl): SourceRepository
    @Binds abstract fun news(impl: NewsRepositoryImpl): NewsRepository
    @Binds abstract fun ai(impl: AiRepositoryImpl): AiRepository
}
