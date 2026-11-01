package ru.calorica.platform.security

import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import ru.calorica.accounts.application.AccountsService
import java.time.Clock
import ru.calorica.platform.api.ApiProblems

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
    @Bean
    fun securityFilterChain(http: HttpSecurity, problems: ApiProblems, accounts: AccountsService, limits: AuthRateLimiter, addresses: ClientAddress): SecurityFilterChain = http
        .addFilterBefore(AuthenticationFilter(accounts, limits, problems, addresses), AnonymousAuthenticationFilter::class.java)
        .csrf { it.disable() } // Stateless API; no cookie authentication exists.
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .requestCache { it.disable() }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .logout { it.disable() }
        .authorizeHttpRequests {
            it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness").permitAll()
                .requestMatchers(HttpMethod.POST, *AuthenticationFilter.PUBLIC_AUTH).permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { request, response, _ -> problems.write(401, request, response) }
            it.accessDeniedHandler { request, response, _ -> problems.write(403, request, response) }
        }
        .build()
}
