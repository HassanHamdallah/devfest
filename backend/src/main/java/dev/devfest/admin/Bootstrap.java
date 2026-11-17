package dev.devfest.admin;

import dev.devfest.contest.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@org.springframework.core.annotation.Order(10)
public class Bootstrap implements CommandLineRunner {

  private static final String DEVELOPMENT_ADMIN_NAME = "Development administrator";
  private static final String PRODUCTION_ADMIN_NAME = "Event administrator";

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final ContestService contest;
  private final ObjectMapper json;
  private final String email, password;
  private final boolean seed, development;

  public Bootstrap(
    JdbcTemplate db,
    PasswordEncoder passwords,
    ContestService contest,
    ObjectMapper json,
    @Value("${app.bootstrap-email}") String email,
    @Value("${app.bootstrap-password}") String password,
    @Value("${app.seed}") boolean seed,
    @Value("${app.development}") boolean development
  ) {
    this.db = db;
    this.passwords = passwords;
    this.contest = contest;
    this.json = json;
    this.email = email;
    this.password = password;
    this.seed = seed;
    this.development = development;
  }

  public void run(String... args) throws Exception {
    // An administrator created by an earlier start in production keeps the old development label otherwise.
    if (!development) {
      db.update(
        "UPDATE app_user SET name=? WHERE role='ADMIN' AND name=?",
        PRODUCTION_ADMIN_NAME,
        DEVELOPMENT_ADMIN_NAME
      );
    }
    if (
      !email.isBlank() &&
      db.queryForObject(
        "SELECT count(*) FROM app_user WHERE role='ADMIN'",
        Long.class
      ) == 0
    ) {
      if (
        password.length() < 12 ||
        password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
          72 ||
        password.contains("REPLACE_")
      ) throw new IllegalStateException(
        "Set a unique bootstrap administrator password"
      );
      db.update(
        "INSERT INTO app_user(email,name,password_hash,role) VALUES(?,?,?,'ADMIN')",
        email.toLowerCase(Locale.ROOT),
        development ? DEVELOPMENT_ADMIN_NAME : PRODUCTION_ADMIN_NAME,
        passwords.encode(password)
      );
    }
    if (
      seed && db.queryForObject("SELECT count(*) FROM problem", Long.class) == 0
    ) {
      Long admin = db.queryForObject(
        "SELECT min(id) FROM app_user WHERE role='ADMIN'",
        Long.class
      );
      if (admin == null) throw new IllegalStateException(
        "Bootstrap an administrator before loading examples"
      );
      for (String file : List.of("sum.json", "balanced.json")) {
        try (var in = getClass().getResourceAsStream("/examples/" + file)) {
          contest.saveProblem(
            null,
            json.readValue(in, ProblemData.class),
            admin
          );
        }
      }
    }
  }
}
