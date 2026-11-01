package com.rurkk.calorica.core.api

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

typealias Decimal = @Serializable(with = DecimalSerializer::class) BigDecimal

/** Contract 1.0.0: decimal is a JSON string, never a binary floating point number. */
object DecimalSerializer : KSerializer<BigDecimal> {
  override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)
  private val syntax = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
  fun parse(value: String): BigDecimal {
    if (value.length > 1103 || !syntax.matches(value)) throw SerializationException("Invalid decimal")
    return value.toBigDecimal()
  }
  override fun deserialize(decoder: Decoder): BigDecimal {
    val primitive = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        ?: throw SerializationException("Decimal must be a JSON string")
    if (!primitive.isString) throw SerializationException("Decimal must be a JSON string")
    return parse(primitive.content)
  }
  override fun serialize(encoder: Encoder, value: BigDecimal) {
    encoder.encodeString(value.toPlainString())
  }
}

fun BigDecimal.toExactPercent(): BigDecimal = movePointRight(2)
fun BigDecimal.toExactFraction(): BigDecimal = movePointLeft(2)
