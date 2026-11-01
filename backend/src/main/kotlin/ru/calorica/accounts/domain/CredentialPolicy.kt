package ru.calorica.accounts.domain

import jakarta.validation.constraints.Email
import jakarta.validation.Validator
import org.springframework.stereotype.Component
import ru.calorica.platform.api.ApiFailure
import java.util.Locale

@Component
class CredentialPolicy(private val validator: Validator) {
    private class EmailValue(@field:Email val email: String)
    fun normalize(credentials: Credentials): String {
        val email = credentials.email.trim().lowercase(Locale.ROOT)
        if (email.isEmpty() || credentials.email.codePointCount(0, credentials.email.length) > 254 ||
            email.length > 254 || validator.validate(EmailValue(email)).isNotEmpty()) throw ApiFailure.invalid("email")
        if (credentials.password.codePointCount(0, credentials.password.length) !in 8..128)
            throw ApiFailure.invalid("password")
        return email
    }
}
