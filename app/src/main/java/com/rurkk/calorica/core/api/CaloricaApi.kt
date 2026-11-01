package com.rurkk.calorica.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.*

val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = true }
const val API_CONTRACT_VERSION = "1.0.0"

/** Shared transport only. Session restoration and auth UI belong to T04. */
interface CaloricaApi {
  @POST("api/v1/accounts/register") suspend fun register(@Body request: Credentials): Response<Tokens>
  @POST("api/v1/accounts/login") suspend fun login(@Body request: Credentials): Response<Tokens>
  @POST("api/v1/accounts/refresh") suspend fun refresh(@Body request: Refresh): Response<Tokens>
  @POST("api/v1/accounts/logout") suspend fun logout(): Response<Unit>
  @GET("api/v1/accounts/me") suspend fun me(): Response<Account>
  @GET("api/v1/products") suspend fun search(@Query("q") q: String?, @Query("limit") limit: Int, @Query("cursor") cursor: String?): Response<ProductPage>
  @GET("api/v1/products/{productId}") suspend fun product(@Path("productId") id: String): Response<Product>
  @POST("api/v1/products") suspend fun createProduct(@Body request: JsonObject): Response<Product>
  @PATCH("api/v1/products/{productId}") suspend fun patchProduct(@Path("productId") id: String, @Body request: JsonObject): Response<Product>
  @PUT("api/v1/products/{productId}/composition") suspend fun composition(@Path("productId") id: String, @Body request: JsonObject): Response<Product>
  @DELETE("api/v1/products/{productId}") suspend fun deleteProduct(@Path("productId") id: String, @Query("mode") mode: String): Response<Unit>
  @GET("api/v1/diary") suspend fun day(@Query("date") date: String, @Query("zone") zone: String): Response<DiaryDay>
  @POST("api/v1/diary/entries") suspend fun createDiary(@Body request: DiaryWrite): Response<DiaryEntry>
  @PATCH("api/v1/diary/entries/{entryId}") suspend fun patchDiary(@Path("entryId") id: String, @Body request: JsonObject): Response<DiaryEntry>
  @DELETE("api/v1/diary/entries/{entryId}") suspend fun deleteDiary(@Path("entryId") id: String): Response<Unit>
  @GET("actuator/health/readiness") suspend fun readiness(): Response<Readiness>
}
