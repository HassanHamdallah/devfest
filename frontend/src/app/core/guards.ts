import { inject } from "@angular/core";
import { CanActivateFn, Router } from "@angular/router";
import { Api } from "./api";
export const authGuard: CanActivateFn = async () => {
  const api = inject(Api),
    router = inject(Router);
  return (await api.restore()) || router.parseUrl("/login");
};
export const adminGuard: CanActivateFn = async () => {
  const api = inject(Api),
    router = inject(Router);
  return (
    ((await api.restore()) && api.user()?.role === "ADMIN") ||
    router.parseUrl("/contest")
  );
};
export const investigationGuard: CanActivateFn = async () => {
  const api = inject(Api),
    router = inject(Router);
  if (!(await api.restore())) return router.parseUrl("/login");
  return api.user()?.role !== "PARTICIPANT" || router.parseUrl("/contest");
};
export const codingGuard: CanActivateFn = async () => {
  const api = inject(Api),
    router = inject(Router);
  if (!(await api.restore())) return router.parseUrl("/login");
  return (
    ["ADMIN", "PARTICIPANT"].includes(api.user()?.role || "") ||
    router.parseUrl("/investigation")
  );
};
