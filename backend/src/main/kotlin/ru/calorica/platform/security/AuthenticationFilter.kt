package ru.calorica.platform.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import ru.calorica.accounts.application.AccountsService
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.api.ApiProblems

class AuthenticationFilter(
    private val accounts: AccountsService, private val limits: AuthRateLimiter, private val problems: ApiProblems,
    private val addresses: ClientAddress,
) : OncePerRequestFilter() {
    override fun shouldNotFilterAsyncDispatch() = false
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.servletPath
        if (request.method == "POST" && path in PUBLIC_AUTH) {
            // Never trust an arbitrary client-supplied X-Forwarded-For header.
            val retry = limits.retryAfter(addresses.resolve(request), path.endsWith("/refresh"))
            if (retry != null) {
                response.setHeader("Retry-After", retry.toString())
                problems.write(429, request, response); return
            }
        }
        response.setHeader("Cache-Control", "no-store")
        val header = request.getHeader("Authorization")
        if (header != null) {
            try {
                if (!header.startsWith("Bearer ", ignoreCase = true)) throw ApiFailure.unauthorized()
                val actor = accounts.authenticate(header.substring(7))
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                    actor, null, listOf(SimpleGrantedAuthority("ROLE_${actor.role.name}")))
            } catch (failure: ApiFailure) { problems.write(failure.status, request, response); return }
            catch (_: org.springframework.dao.DataAccessException) { problems.write(503, request, response); return }
        }
        chain.doFilter(request, response)
    }
    companion object {
        val PUBLIC_AUTH = arrayOf("/api/v1/accounts/register", "/api/v1/accounts/login", "/api/v1/accounts/refresh")
    }
}
