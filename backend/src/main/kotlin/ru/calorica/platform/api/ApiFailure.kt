package ru.calorica.platform.api

data class FieldFailure(val field: String, val code: String)

/** Contains protocol codes only; never credentials, rejected values or database messages. */
class ApiFailure(
    val status: Int,
    val code: String,
    val errors: List<FieldFailure> = emptyList(),
) : RuntimeException(code) {
    companion object {
        fun invalid(field: String) = ApiFailure(422, "VALIDATION_FAILED", listOf(FieldFailure(field, "INVALID_VALUE")))
        fun unauthorized() = ApiFailure(401, "UNAUTHORIZED")
    }
}
