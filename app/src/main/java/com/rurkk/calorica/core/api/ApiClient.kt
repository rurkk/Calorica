package com.rurkk.calorica.core.api

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

const val REQUEST_TIMEOUT_MILLIS = 10_000L

@Singleton class InMemorySession @Inject constructor() {
  @Volatile private var token: String? = null
  fun accessToken(): String? = token
  fun replaceAccessToken(value: String?) { token = value }
  fun clear() { token = null }
}

object ApiClient {
  fun http(@Suppress("UNUSED_PARAMETER") session: InMemorySession, baseUrl: String): OkHttpClient {
    val origin = baseUrl.toHttpUrl()
    val anonymous = setOf("/api/v1/accounts/register", "/api/v1/accounts/login", "/api/v1/accounts/refresh")
    return OkHttpClient.Builder()
      .callTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      .connectTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      .readTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      .writeTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
      // Never replay writes after an ambiguous transport failure. With retries disabled,
      // a pooled TLS socket closed during server/proxy idle time can fail the next action
      // before it reaches the server. Retire idle sockets instead of guessing that timeout.
      .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
      .retryOnConnectionFailure(false)
      .followRedirects(false).followSslRedirects(false)
      .addInterceptor { chain ->
        val request = chain.request().newBuilder().header("Accept", "application/json, application/problem+json")
        val url = chain.request().url
        // Authentication was bound to the operation before enqueueing. Never replace it
        // with a mutable token here: an old request could otherwise execute as a new account.
        if (!(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
            url.encodedPath.startsWith("/api/v1/") && url.encodedPath !in anonymous)) {
          request.removeHeader("Authorization")
        }
        chain.proceed(request.build())
      }.build()
  }

  /** allowHttp is exclusively for isolated MockWebServer tests, never DI/production. */
  fun create(baseUrl: String, http: OkHttpClient, allowHttp: Boolean = false): CaloricaApi {
    val url = baseUrl.toHttpUrl()
    require((allowHttp || url.isHttps) && url.username.isEmpty() && url.password.isEmpty() &&
        url.query == null && url.fragment == null && url.encodedPath == "/") { "Expected an HTTPS origin" }
    return Retrofit.Builder().baseUrl(url).client(http)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build().create(CaloricaApi::class.java)
  }
}

sealed interface ApiResult<out T> {
  data class Success<T>(val value: T) : ApiResult<T>
  data class Failure(val error: ApiError) : ApiResult<Nothing>
}

data class ApiError(
    val kind: Kind, val status: Int? = null, val code: String? = null,
    val correlationId: String? = null, val fields: List<FieldError> = emptyList(), val retryAfterSeconds: Long? = null,
) {
  enum class Kind { NETWORK, TIMEOUT, HTTP, INVALID_RESPONSE }
  fun message(): String = when (kind) {
    Kind.NETWORK -> "Не удалось связаться с сервером. Проверьте подключение к сети."
    Kind.TIMEOUT -> "Сервер не ответил за 10 секунд. Повторите загрузку."
    Kind.INVALID_RESPONSE -> "Не удалось прочитать ответ сервера."
    Kind.HTTP -> when (code) {
      "UNAUTHORIZED" -> "Войдите в аккаунт."
      "FORBIDDEN" -> "Недостаточно прав для этого действия."
      "NOT_FOUND" -> "Данные не найдены."
      "EMAIL_CONFLICT" -> "Этот email уже используется."
      "CYCLE_DETECTED" -> "Состав содержит цикл."
      "YIELD_MODE_CONFLICT" -> "Выберите один способ задания выхода."
      "DEPENDENCY_CONFLICT" -> "Продукт используется в других составах."
      "INVALID_COMPOSITION" -> "Проверьте состав и сумму процентов."
      "PRECISION_EXCEEDED" -> "Слишком много знаков после запятой."
      "UNIT_UNAVAILABLE" -> "Эта единица измерения недоступна."
      "VALIDATION_FAILED", "BAD_REQUEST" -> "Проверьте введённые данные."
      "REQUEST_TOO_LARGE" -> "Запрос слишком большой."
      "TOO_MANY_REQUESTS" -> "Слишком много запросов. Попробуйте позже."
      "CALCULATION_LIMIT" -> "Превышен допустимый размер расчёта."
      "CALCULATION_TIMEOUT", "SERVICE_UNAVAILABLE" -> "Сервер временно недоступен."
      else -> "Не удалось выполнить запрос."
    }
  }
  fun writeMessage(): String = if (kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.INVALID_RESPONSE ||
      status == null || status >= 500) "Результат сохранения неизвестен. Обновите данные перед повторной попыткой." else message()
  fun fieldMessage(error: FieldError): String = when (error.code) {
    "PERCENT_SUM" -> "Сумма процентов должна быть равна 100."
    "PRECISION_EXCEEDED" -> "Слишком много знаков после запятой."
    else -> "Проверьте значение поля."
  }
}

@Singleton class ApiExecutor @Inject constructor() {
  suspend fun <T : Any> call(block: suspend () -> Response<T>): ApiResult<T> = execute(false, block)
  suspend fun empty(block: suspend () -> Response<Unit>): ApiResult<Unit> = execute(true, block)

  private suspend fun <T : Any> execute(emptyAllowed: Boolean, block: suspend () -> Response<T>): ApiResult<T> = try {
    // Includes dispatcher queue/DNS/conversion, in addition to OkHttp's whole-call deadline.
    withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
      val response = block()
      if (!response.isSuccessful) {
        val problem = response.errorBody()?.use { body ->
          // Bound error parsing; never retain or display server detail/stack/HTML.
          val source = body.source()
          source.request(65_537)
          if (source.buffer.size > 65_536) null
          else runCatching { ApiJson.decodeFromString<Problem>(source.readUtf8()) }.getOrNull()
        }
        val id = (problem?.correlationId ?: response.headers()["X-Request-Id"])
            ?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        ApiResult.Failure(ApiError(ApiError.Kind.HTTP, response.code(), problem?.code, id,
            problem?.errors.orEmpty(), response.headers()["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }))
      } else {
        val body = response.body()
        if (body != null) ApiResult.Success(body)
        else if (emptyAllowed && response.code() == 204) {
          @Suppress("UNCHECKED_CAST")
          ApiResult.Success(Unit as T)
        } else ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
      }
    } ?: ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT))
  } catch (e: CancellationException) {
    throw e
  } catch (e: InterruptedIOException) {
    ApiResult.Failure(ApiError(ApiError.Kind.TIMEOUT))
  } catch (e: IOException) {
    ApiResult.Failure(ApiError(ApiError.Kind.NETWORK))
  } catch (e: SerializationException) {
    ApiResult.Failure(ApiError(ApiError.Kind.INVALID_RESPONSE))
  }
}
