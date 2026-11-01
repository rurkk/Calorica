package ru.calorica.accounts.application

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.UUID
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import ru.calorica.accounts.api.AccountsController
import ru.calorica.accounts.domain.Account
import ru.calorica.accounts.domain.CredentialPolicy
import ru.calorica.accounts.domain.Credentials
import ru.calorica.accounts.domain.Refresh
import ru.calorica.accounts.domain.Tokens
import ru.calorica.platform.security.AccessTokens
import ru.calorica.platform.security.Role
import ru.calorica.profile.api.ProfileController
import ru.calorica.profile.domain.Profile

/** Initialize lazy auth codecs before Boot publishes ACCEPTING_TRAFFIC, without database writes. */
@Component
class AuthInitialization(
    private val mapper: ObjectMapper, private val policy: CredentialPolicy,
    private val passwords: PasswordHashing, private val access: AccessTokens, private val clock: Clock,
    private val mvc: RequestMappingHandlerAdapter,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        for (controller in listOf(AccountsController::class.java, ProfileController::class.java)) {
            for (method in controller.declaredMethods) {
                val result = MethodParameter(method, -1)
                result.parameterType
                result.genericParameterType
                result.isOptional
            }
        }
        // Only in-memory samples. These UUIDs have no account/session rows and cannot authenticate.
        val credentials = mapper.readValue(
            """{"email":"initialization@example.org","password":"initialization-only"}""", Credentials::class.java)
        val email = policy.normalize(credentials)
        // A fixed, small number of real operations also exercises the memory-hard hot loop
        // before a low-CPU instance starts serving requests; no password parameters are reduced.
        repeat(3) {
            val hash = passwords.encode(credentials.password)
            check(passwords.matches(credentials.password, hash))
        }
        val accountId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val expires = clock.instant().plusSeconds(30 * 86400)
        val issued = access.issue(accountId, sessionId, expires)
        check(access.verify(issued.value) == AccessTokens.Identity(accountId, sessionId))
        val refresh = mapper.readValue("""{"refreshToken":"initialization-only"}""", Refresh::class.java)
        val profile = Profile(null, null, null, null)
        val sample = Tokens(issued.value, refresh.refreshToken, issued.expiresAt, expires,
            Account(accountId, email, Role.USER, profile))
        mapper.writeValueAsBytes(sample)
        for (converter in mvc.messageConverters.filterIsInstance<MappingJackson2HttpMessageConverter>()) {
            check(converter.canWrite(Tokens::class.java, Tokens::class.java, MediaType.APPLICATION_JSON))
            converter.write(sample, Tokens::class.java, MediaType.APPLICATION_JSON, object : HttpOutputMessage {
                private val headers = HttpHeaders()
                private val body = ByteArrayOutputStream()
                override fun getHeaders() = headers
                override fun getBody() = body
            })
            converter.read(Credentials::class.java, object : HttpInputMessage {
                override fun getHeaders() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
                override fun getBody() = ByteArrayInputStream("""{"email":"initialization@example.org","password":"initialization-only"}""".toByteArray())
            })
        }
        // The timeout filter uses this mapper directly. Initialize its response shape as well.
        mapper.writeValueAsBytes(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE).apply {
            setProperty("code", "SERVICE_UNAVAILABLE")
            setProperty("correlationId", UUID.randomUUID().toString())
        })
    }
}
