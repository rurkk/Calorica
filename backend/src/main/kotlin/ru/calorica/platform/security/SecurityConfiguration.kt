package ru.calorica.platform.security

import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import ru.calorica.platform.api.ApiProblems

@Configuration
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity, problems: ApiProblems): SecurityFilterChain = http
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
                .anyRequest().denyAll()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { request, response, _ -> problems.write(401, request, response) }
            it.accessDeniedHandler { request, response, _ -> problems.write(403, request, response) }
        }
        .build()
}
