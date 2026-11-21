import { inject } from "@angular/core";
import { CanActivateFn, RedirectCommand, Router } from "@angular/router";
import { Api } from "./api";
import { RoleHome, UserRole } from "./role-home";

/** Signed-out users go to /login; signed-in users without one of `roles` go to their role home. */
function roleGuard(roles: readonly UserRole[]): CanActivateFn {
  return async () => {
    const api = inject(Api);
    const router = inject(Router);
    const roleHome = inject(RoleHome);
    if (!(await api.restore())) return router.parseUrl("/login");
    const role = api.user()?.role;
    if (role && roles.includes(role)) return true;
    return router.parseUrl(await roleHome.url());
  };
}

export const eventAdminGuard = roleGuard(["ADMIN"]);
export const managerGuard = roleGuard(["MANAGER", "ADMIN"]);
export const memberGuard = roleGuard(["MEMBER"]);
export const eventResultsGuard = roleGuard(["MEMBER", "ADMIN"]);
export const codingGuard = roleGuard(["PARTICIPANT", "ADMIN"]);
/** Old coding-contest organizer pages. */
export const adminGuard = roleGuard(["ADMIN"]);

/**
 * For /login only. A user who is already signed in (Back button, a typed address, a refresh) goes to their role home,
 * the same place the login page sends them after signing in; the redirect replaces the /login history entry, so Back
 * does not bounce between the two. It waits for the same session restore as the guards above. Signed out, or a session
 * that cannot be restored, shows the form.
 */
export const loginGuard: CanActivateFn = async () => {
  const api = inject(Api);
  const router = inject(Router);
  const roleHome = inject(RoleHome);
  if (!(await api.restore())) return true;
  const home = await roleHome.url();
  if (home === "/login") return true; // no home for this role: never redirect to ourselves
  return new RedirectCommand(router.parseUrl(home), { replaceUrl: true });
};

/** For '' and '**': never activates, always redirects to the role home (or /login). */
export const roleHomeGuard: CanActivateFn = async () => {
  const api = inject(Api);
  const router = inject(Router);
  const roleHome = inject(RoleHome);
  if (!(await api.restore())) return router.parseUrl("/login");
  return router.parseUrl(await roleHome.url());
};
