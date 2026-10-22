# TeamQora reuse audit - 2026-10-20

Source inspected read-only: `C:/Users/hassa/OneDrive/Desktop/teamqora`. Destination is independent. Source tests were inspected, not executed; old reports are not evidence. README, backend/frontend CLAUDE.md, PRD and class diagrams were read. The user-authorized audit supersedes source no-scanning guidelines; company-domain rules do not apply to the new product. No AGENTS.md found. No deployment compose/Docker configuration or root license found.

Versions: Java source 25, Spring Boot parent 4.1.1, JJWT 0.12.6, Maven wrapper 3.9.16. Angular/TypeScript exact versions are pinned from the original package-lock into frontend/package.json. Host: Temurin 25.0.4.1, Node 24.15.0, npm 11.12.1; PostgreSQL 18 binaries installed. Docker CLI exists but daemon is unavailable. No source database accessed.

Paths below are relative to source/destination respectively. All source features in this table are source-inspected, not independently proven working in TeamQora. Destination verification is recorded below and in STATUS.md.

| Area / original files | Purpose / decision / dependencies | Destination | Limitations and verification |
|---|---|---|---|
| backend/mvnw*, .mvn/wrapper, pom.xml | Reuse wrapper; adapt Boot/JJWT/PostgreSQL/Flyway dependencies; remove mail/Lombok/company entities | backend/ | Build results in STATUS; use JDBC for explicit queue locking instead of ORM inheritance |
| security/JwtService.java, JwtProperties.java | Adapt HMAC signing; add required issuer, mandatory secret and session ID | backend/src/main/java/dev/devfest/auth/ | Original parser did not require issuer; new tests required |
| security/JwtAuthenticationFilter.java, CustomUserDetails*, config/SecurityConfig.java | Adapt database active-account checks, BCrypt, bearer-only security, server roles, CORS | auth/SecurityConfig.java, auth/AuthService.java | Remove company membership; session lookup makes logout/password change immediate |
| service/impl/AuthServiceImpl.java, repository/RefreshTokenRepository.java, model/entity/RefreshToken.java, db/migration/V3* | Adapt login/logout/refresh rotation and SHA-256 token storage | auth/AuthService.java; new V1 schema | Original revoke-then-throw could roll back; new transaction semantics commit replay revocation; serialize rotation |
| service/impl/PasswordResetServiceImpl.java, V23*, ChangePassword DTO/controller | Reuse password policy/hash/revocation ideas; defer email reset | auth/AuthService.java, AdminController.java | Own password change + admin reset supported; no SMTP dependency |
| frontend/core/services/auth*.ts, token-storage.service.ts, core/http/auth.interceptor.ts, core/guards/* | Adapt signal auth state, single-flight refresh and route protection | frontend/src/app/core/api.ts, guards.ts | Use tab sessionStorage for tokens; XSS risk remains; no cookies/CSRF flow |
| dto/*, service/mapper/*, core/models/*, core/pagination.ts | Adapt record DTOs, validation, bounded pagination; exclude question mappings | backend api DTO records; frontend core/models.ts | Explicit response whitelists; never serialize database password/hidden-test rows to participants |
| exception/GlobalExceptionHandler.java, frontend/core/services/problem-detail.util.ts | Adapt RFC ProblemDetail validation and generic error messages | backend/api/Errors.java; frontend/core/api.ts | Unexpected errors sanitized; tests required |
| application.properties, db/migration/V1..V26 | Reuse Flyway discipline/configuration ideas; exclude all source SQL migrations | backend/resources/application.properties, db/migration/V1__devfest.sql | New DB/schema only; immutable contest problem versions |
| shared/ui/{ui-button,ui-spinner,ui-input,ui-select,ui-textarea,ui-card,ui-alert,ui-badge,ui-empty-state,ui-pagination} | Copy presentational components including dependencies; Angular forms/signals | frontend/src/app/shared/ui/ | Build and browser checks; copied tests not claimed as current results |
| frontend/src/styles.css, theme.service.ts, shell/navigation components, styleguide | Adapt tokens/base styles and responsive layout ideas | frontend/src/styles.css, app shell | Remove company branding and achievement styling; new contest navigation |
| system-admin/admin-users*, backend/service/impl/AdminUserServiceImpl.java | Adapt account administration concept; dedicated roles | admin/ and admin frontend | Only ADMIN creates PARTICIPANT; no public registration |
| ActivityRecorder*, ActivityLog*, V24* | Adapt transaction-bound audit idea | audit_event table + admin writes | No old moderation feed; audit contains IDs/actions, never code/tests/passwords |
| src/test security/controller/service tests; frontend core/UI specs | Reuse test cases conceptually; no old reports copied | new backend tests, browser verification, load-tests/ | Fresh verification only; source tests are strongly coupled to companies |
| Question/Answer/Vote/Reputation/Badge/Bookmark/Company/Invitation/upload/mail features | Exclude unrelated product and tenant dependencies | none | No user data, uploads, secrets, caches, .git or build outputs copied |

Useful deferred foundations: emailed password reset, full theme switcher, rich audit viewer, complex admin filters. Basic states and accessible shared controls are retained.


## Completed destination verification

All evidence below was generated in DevFest, using its own dependencies and databases. Source inspection does not establish that the corresponding TeamQora features passed tests.

| Reused/adapted foundation | Verification performed | Remaining limit |
|---|---|---|
| Maven wrapper, dependency/configuration patterns and Flyway | Backend package succeeded; new development/test schemas migrated; 6 startup-safety tests passed | Second-machine installation and optional Docker route unverified |
| Authentication, BCrypt, JWT, refresh/session rotation and account administration | 17 PostgreSQL integration tests cover auth/role/ownership, refresh replay, concurrent rotation, deactivation and password revocation alongside contest/queue cases | Email reset deferred; browser matrix is limited |
| Angular auth state, guards and request handling | Three browser journeys cover login/logout, expiration refresh/redirect, admin navigation and draft recovery | sessionStorage remains XSS-readable |
| DTOs, validation, transactions and errors | Unknown fields/escalation rejected; participant private resources denied; hidden tests excluded in integration checks | Not every input combination exhaustively tested |
| Shared UI, styles, forms, layout and pagination | Final production build passed; desktop screenshots inspected; mobile overflow checked; participant/admin browser flows passed | Formal accessibility audit and richer themes deferred; documented Monaco teardown warning |
| Password reset/account admin concepts | API password revocation tested; admin forms implemented | Email reset and own-password UI deferred; full admin browser matrix remains |
| Audit recording concept | Writes remain part of admin transactions; source inspection | Rich audit viewer and retention automation deferred |
| Test and tooling concepts | 30 new backend tests across three runs; 3 browser tests; 14 real-provider cases; actual process recovery; separate 80-user browse/draft checks | No copied reports; 80-user execution and event isolation remain unverified |

Pinned frontend runtime: Angular 22.1.4, builder/CLI 22.1.6, TypeScript 6.0.3, RxJS 7.8.2, Monaco 0.55.1. PostgreSQL actually exercised: native Windows 18.6. Final build, test names, evidence files and exact operating limitations are in STATUS.md and README.md.
