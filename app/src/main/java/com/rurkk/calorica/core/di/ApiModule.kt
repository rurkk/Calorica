package com.rurkk.calorica.core.di

import com.rurkk.calorica.BuildConfig
import com.rurkk.calorica.core.api.*
import com.rurkk.calorica.core.data.*
import com.rurkk.calorica.core.session.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module @InstallIn(SingletonComponent::class)
object ApiModule {
  @Provides @Singleton fun http(session: InMemorySession): OkHttpClient = ApiClient.http(session, BuildConfig.API_BASE_URL)
  @Provides @Singleton fun api(http: OkHttpClient): CaloricaApi = ApiClient.create(BuildConfig.API_BASE_URL, http)
}
@Module @InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
  @Binds abstract fun refreshStore(store: KeystoreRefreshTokenStore): RefreshTokenStore
  @Binds abstract fun auth(repository: HttpAuthRepository): AuthRepository
  @Binds abstract fun products(repository: SessionProductRepository): ProductRepository
  @Binds abstract fun diary(repository: SessionDiaryRepository): DiaryRepository
  @Binds abstract fun connection(repository: HttpConnectionRepository): ConnectionRepository
}
