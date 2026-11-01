package ru.calorica.platform.api

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.MapperFeature
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JsonConfiguration {
    @Bean
    fun strictJson() = Jackson2ObjectMapperBuilderCustomizer {
        it.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        it.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        it.postConfigurer { mapper ->
            val text = mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
            for (shape in listOf(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
                text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        }
    }
}
