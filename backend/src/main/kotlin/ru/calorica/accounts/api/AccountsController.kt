package ru.calorica.accounts.api

import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import ru.calorica.accounts.application.AccountsService
import ru.calorica.accounts.domain.Credentials
import ru.calorica.accounts.domain.Refresh
import ru.calorica.platform.security.Actor

@RestController
@RequestMapping("/api/v1/accounts")
class AccountsController(private val accounts: AccountsService) {
    @PostMapping("/register", consumes = ["application/json"])
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody credentials: Credentials) = accounts.register(credentials)
    @PostMapping("/login", consumes = ["application/json"])
    fun login(@RequestBody credentials: Credentials) = accounts.login(credentials)
    @PostMapping("/refresh", consumes = ["application/json"])
    fun refresh(@RequestBody refresh: Refresh) = accounts.refresh(refresh.refreshToken)
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal actor: Actor) = accounts.me(actor)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal actor: Actor) = accounts.logout(actor)
}
