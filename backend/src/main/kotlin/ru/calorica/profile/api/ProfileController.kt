package ru.calorica.profile.api

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.calorica.platform.security.Actor
import ru.calorica.profile.application.ProfileService

@RestController
class ProfileController(private val profiles: ProfileService) {
    @PatchMapping("/api/v1/profile", consumes = ["application/json"])
    fun patch(@AuthenticationPrincipal actor: Actor, @RequestBody body: JsonNode) = profiles.patch(actor, body)
}
