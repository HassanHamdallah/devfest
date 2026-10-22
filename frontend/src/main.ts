import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { provideRouter } from "@angular/router";
import { App } from "./app/app";
import { codingGuard, adminGuard, investigationGuard } from "./app/core/guards";
import { authInterceptor } from "./app/core/api";
bootstrapApplication(App, {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter([
      {
        path: "investigation",
        canActivate: [investigationGuard],
        loadComponent: () =>
          import("./app/features/investigation/overview").then(
            (m) => m.InvestigationOverview,
          ),
      },
      {
        path: "investigation/rooms/:id",
        canActivate: [investigationGuard],
        loadComponent: () =>
          import("./app/features/investigation/room").then(
            (m) => m.InvestigationRoom,
          ),
      },
      {
        path: "investigation/rooms/:room/challenges/:id",
        canActivate: [investigationGuard],
        loadComponent: () =>
          import("./app/features/investigation/answer").then(
            (m) => m.InvestigationAnswer,
          ),
      },
      {
        path: "login",
        loadComponent: () =>
          import("./app/features/login").then((m) => m.Login),
      },
      {
        path: "contest",
        canActivate: [codingGuard],
        loadComponent: () =>
          import("./app/features/contest").then((m) => m.ContestPage),
      },
      {
        path: "problems",
        canActivate: [codingGuard],
        loadComponent: () =>
          import("./app/features/problems").then((m) => m.Problems),
      },
      {
        path: "problems/:id",
        canActivate: [codingGuard],
        loadComponent: () =>
          import("./app/features/workspace").then((m) => m.Workspace),
      },
      {
        path: "submissions",
        canActivate: [codingGuard],
        loadComponent: () =>
          import("./app/features/submissions").then((m) => m.Submissions),
      },
      {
        path: "scoreboard",
        canActivate: [codingGuard],
        loadComponent: () =>
          import("./app/features/scoreboard").then((m) => m.Scoreboard),
      },
      {
        path: "admin/submissions",
        canActivate: [adminGuard],
        loadComponent: () =>
          import("./app/features/submissions").then((m) => m.Submissions),
      },
      ...[
        { path: "overview", section: "Overview" },
        { path: "participants", section: "Participants" },
        { path: "problems", section: "Problems" },
        { path: "contest", section: "Contest Settings" },
      ].map((x) => ({
        path: "admin/" + x.path,
        canActivate: [adminGuard],
        data: { section: x.section },
        loadComponent: () =>
          import("./app/features/admin").then((m) => m.Admin),
      })),
      { path: "", pathMatch: "full", redirectTo: "contest" },
      { path: "**", redirectTo: "contest" },
    ]),
  ],
}).catch(console.error);
