package dev.devfest.investigation;

import dev.devfest.auth.AuthService.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit development opt-in only; existing rooms and accounts are never overwritten. */
@Component
@Order(20)
public class InvestigationSeed implements CommandLineRunner {

  private final JdbcTemplate db;
  private final InvestigationOrganization organization;
  private final InvestigationChallenges challenges;
  private final String password;
  private final boolean development;

  public InvestigationSeed(
    JdbcTemplate db,
    InvestigationOrganization organization,
    InvestigationChallenges challenges,
    @Value("${DEVFEST_INVESTIGATION_SEED_PASSWORD:}") String password,
    @Value("${app.development}") boolean development
  ) {
    this.db = db;
    this.organization = organization;
    this.challenges = challenges;
    this.password = password;
    this.development = development;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (password.isBlank()) return;
    if (!development) throw new IllegalStateException(
      "Investigation seed is restricted to development mode."
    );
    challenges.lockSettings();
    if (db.queryForObject("SELECT count(*) FROM room", Long.class) > 0) return;
    Long admin = db.queryForObject(
      "SELECT min(id) FROM app_user WHERE role='ADMIN'",
      Long.class
    );
    if (admin == null) throw new IllegalStateException(
      "Bootstrap an administrator before investigation seeds."
    );
    var p = new Principal(admin, "ADMIN", UUID.randomUUID());
    long manager = organization.account(
      p,
      new InvestigationOrganization.Account(
        "manager@investigation.test",
        "Development manager",
        password,
        "MANAGER"
      )
    );
    for (int i = 1; i <= 2; i++) {
      long member = organization.account(
        p,
        new InvestigationOrganization.Account(
          "member" + i + "@investigation.test",
          "Development representative " + i,
          password,
          "MEMBER"
        )
      );
      long room = organization.room(
        p,
        null,
        new InvestigationOrganization.RoomEdit("Development room " + i, false)
      );
      organization.managers(
        p,
        room,
        new InvestigationOrganization.Assignment(List.of(manager))
      );
      organization.team(
        p,
        null,
        new InvestigationOrganization.TeamEdit(
          "Development team " + i,
          room,
          member,
          false
        )
      );
      for (var kind : InvestigationChallenges.Kind.values())
        challenges.save(
          p,
          room,
          null,
          new InvestigationChallenges.Edit(
            "Development " + kind,
            "Practice only: describe a conclusion and the evidence supporting it.",
            List.of(),
            kind == InvestigationChallenges.Kind.FINAL
              ? "Conclusion, reasoning, supporting evidence"
              : "Text answer",
            "Practice rubric: conclusion 30, reasoning 40, evidence 30. Reviewer awards up to 100 total points.",
            100,
            kind,
            InvestigationChallenges.State.DRAFT,
            Instant.now(),
            Instant.now().plusSeconds(86400),
            0
          )
        );
    }
  }
}
