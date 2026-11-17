import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { Routes, provideRouter } from "@angular/router";
import { App } from "./app/app";
import {
  adminGuard,
  codingGuard,
  eventAdminGuard,
  eventResultsGuard,
  managerGuard,
  memberGuard,
  roleHomeGuard,
} from "./app/core/guards";
import { authInterceptor } from "./app/core/api";

const eventRoutes: Routes = [
  {
    path: "event/admin",
    pathMatch: "full",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-control.page").then(
        (m) => m.EventAdminControlPage,
      ),
  },
  {
    path: "event/admin/teams",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-teams.page").then(
        (m) => m.EventAdminTeamsPage,
      ),
  },
  {
    path: "event/admin/overrides",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-overrides.page").then(
        (m) => m.EventAdminOverridesPage,
      ),
  },
  {
    path: "event/admin/scores",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-scores.page").then(
        (m) => m.EventAdminScoresPage,
      ),
  },
  {
    path: "event/admin/final-reports",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-final-reports.page").then(
        (m) => m.EventAdminFinalReportsPage,
      ),
  },
  {
    path: "event/admin/content",
    canActivate: [eventAdminGuard],
    loadComponent: () =>
      import("./app/features/event/admin/admin-content.page").then(
        (m) => m.EventAdminContentPage,
      ),
  },
  {
    path: "event/room",
    pathMatch: "full",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/manager-home.page").then(
        (m) => m.EventManagerHomePage,
      ),
  },
  {
    path: "event/room/:roomId/mission",
    pathMatch: "full",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/mission/mission.page").then(
        (m) => m.EventMissionPage,
      ),
  },
  {
    path: "event/room/:roomId/mission/guide",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/mission/field-guide.page").then(
        (m) => m.EventFieldGuidePage,
      ),
  },
  {
    path: "event/room/:roomId/contest",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/contest/contest-results.page").then(
        (m) => m.EventContestResultsPage,
      ),
  },
  {
    path: "event/room/:roomId/dashboard",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/terminal-dashboard/terminal-dashboard.page").then(
        (m) => m.EventTerminalDashboardPage,
      ),
  },
  {
    path: "event/room/:roomId",
    canActivate: [managerGuard],
    loadComponent: () =>
      import("./app/features/event/manager/room-board.page").then(
        (m) => m.EventRoomBoardPage,
      ),
  },
  {
    path: "event/team",
    pathMatch: "full",
    canActivate: [memberGuard],
    loadComponent: () =>
      import("./app/features/event/team/team-home.page").then(
        (m) => m.EventTeamHomePage,
      ),
  },
  {
    path: "event/team/final",
    canActivate: [memberGuard],
    loadComponent: () =>
      import("./app/features/event/team/team-final.page").then(
        (m) => m.EventTeamFinalPage,
      ),
  },
  {
    path: "event/results",
    canActivate: [eventResultsGuard],
    loadComponent: () =>
      import("./app/features/event/results/results.page").then(
        (m) => m.EventResultsPage,
      ),
  },
];

const codingRoutes: Routes = [
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
    loadComponent: () => import("./app/features/admin").then((m) => m.Admin),
  })),
];

bootstrapApplication(App, {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter([
      {
        path: "login",
        loadComponent: () =>
          import("./app/features/login").then((m) => m.Login),
      },
      ...eventRoutes,
      ...codingRoutes,
      // Guards redirect by role, so these routes never render anything themselves.
      {
        path: "",
        pathMatch: "full",
        canActivate: [roleHomeGuard],
        children: [],
      },
      { path: "**", canActivate: [roleHomeGuard], children: [] },
    ]),
  ],
}).catch(console.error);
