package dev.devfest.auth;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

  @Bean
  static PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain security(
    HttpSecurity http,
    AuthService auth,
    @Value("${app.origins}") String origins
  ) throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(Arrays.asList(origins.split(",")));
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
    cors.setAllowedHeaders(
      List.of("Authorization", "Content-Type", "Idempotency-Key")
    );
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    var filter = new OncePerRequestFilter() {
      protected void doFilterInternal(
        jakarta.servlet.http.HttpServletRequest req,
        jakarta.servlet.http.HttpServletResponse res,
        jakarta.servlet.FilterChain chain
      ) throws java.io.IOException, jakarta.servlet.ServletException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
          try {
            var p = auth.authenticate(h.substring(7));
            SecurityContextHolder.getContext().setAuthentication(
              new UsernamePasswordAuthenticationToken(
                p,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role()))
              )
            );
          } catch (
            io.jsonwebtoken.JwtException
            | IllegalArgumentException
            | dev.devfest.api.Errors.ApiException ignored
          ) {
            SecurityContextHolder.clearContext();
          }
        }
        chain.doFilter(req, res);
      }
    };
    return http
      .csrf(c -> c.disable())
      .cors(c -> c.configurationSource(source))
      .sessionManagement(c ->
        c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
      )
      .formLogin(c -> c.disable())
      .httpBasic(c -> c.disable())
      .logout(c -> c.disable())
      .authorizeHttpRequests(c ->
        c
          .requestMatchers(
            org.springframework.http.HttpMethod.POST,
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
          )
          .permitAll()
          .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/health")
          .permitAll()
          .requestMatchers("/api/v1/admin/**")
          .hasRole("ADMIN")
          .requestMatchers("/api/v1/auth/**")
          .authenticated()
          .requestMatchers("/api/v1/investigation/**")
          .hasAnyRole("ADMIN", "MANAGER", "MEMBER")
          .requestMatchers("/api/v1/**")
          .hasAnyRole("ADMIN", "PARTICIPANT")
          .anyRequest()
          .denyAll()
      )
      .exceptionHandling(c ->
        c
          .authenticationEntryPoint((q, r, e) ->
            error(r, 401, "Please sign in again.")
          )
          .accessDeniedHandler((q, r, e) -> error(r, 403, "Access denied."))
      )
      .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
      .build();
  }

  private static void error(
    jakarta.servlet.http.HttpServletResponse r,
    int status,
    String detail
  ) throws java.io.IOException {
    r.setStatus(status);
    r.setContentType("application/problem+json");
    r.getWriter().write(
      "{\"status\":" + status + ",\"detail\":\"" + detail + "\"}"
    );
  }
}
