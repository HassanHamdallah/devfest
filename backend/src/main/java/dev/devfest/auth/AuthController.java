package dev.devfest.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService auth;
  private final RateLimit rate;

  public AuthController(AuthService a, RateLimit r) {
    auth = a;
    rate = r;
  }

  public record Login(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 72) String password
  ) {}

  public record Refresh(@NotBlank @Size(max = 256) String refreshToken) {}

  public record Password(
    @NotBlank @Size(max = 72) String currentPassword,
    @NotBlank @Size(min = 12, max = 72) String newPassword
  ) {}

  @PostMapping("/login")
  AuthService.Tokens login(
    @Valid @RequestBody Login r,
    jakarta.servlet.http.HttpServletRequest request
  ) {
    rate.check("login-ip:" + request.getRemoteAddr(), 300);
    rate.check("login-account:" + AuthService.hash(r.email().trim().toLowerCase(java.util.Locale.ROOT)), 10);
    return auth.login(r.email(), r.password());
  }

  @PostMapping("/refresh")
  AuthService.Tokens refresh(
    @Valid @RequestBody Refresh r,
    jakarta.servlet.http.HttpServletRequest request
  ) {
    rate.check("refresh:" + request.getRemoteAddr(), 240);
    return auth.refresh(r.refreshToken());
  }

  @PostMapping("/logout")
  Map<String, Boolean> logout(
    @AuthenticationPrincipal AuthService.Principal p
  ) {
    auth.logout(p);
    return Map.of("ok", true);
  }

  @GetMapping("/me")
  AuthService.User me(@AuthenticationPrincipal AuthService.Principal p) {
    return auth.user(p.id());
  }

  @PostMapping("/password")
  Map<String, Boolean> password(
    @AuthenticationPrincipal AuthService.Principal p,
    @Valid @RequestBody Password r
  ) {
    auth.password(p, r.currentPassword(), r.newPassword());
    return Map.of("ok", true);
  }
}
