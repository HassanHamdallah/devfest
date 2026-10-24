import { Injectable, inject, signal } from "@angular/core";
import {
  HttpClient,
  HttpErrorResponse,
  HttpInterceptorFn,
} from "@angular/common/http";
import { Router } from "@angular/router";
import { firstValueFrom, catchError, switchMap, throwError, from } from "rxjs";
import { Tokens, User } from "./models";
declare global {
  interface Window {
    DEVFEST_API_URL?: string;
  }
}
export const BASE = (window.DEVFEST_API_URL || "/api/v1").replace(/\/$/, "");

/**
 * The server's reason on a 401 for an ended session (null when absent). Only EXPIRED is renewed with the
 * refresh token; every other reason (REPLACED, ORGANIZER, DEACTIVATED, …) means the session is gone.
 */
export function sessionEndReason(e: unknown): string | null {
  if (!(e instanceof HttpErrorResponse) || e.status !== 401) return null;
  const reason: unknown = e.error?.reason;
  return typeof reason === "string" ? reason : null;
}

function isFinalSessionEnd(e: unknown): boolean {
  const reason = sessionEndReason(e);
  return reason !== null && reason !== "EXPIRED";
}
@Injectable({ providedIn: "root" })
export class Api {
  private http = inject(HttpClient);
  private router = inject(Router);
  user = signal<User | null>(null);
  /** Why the last session ended (e.g. signed in on another device); shown once on the login page. */
  readonly sessionNotice = signal<string | null>(null);
  private renewing: Promise<Tokens> | null = null;
  request<T>(
    method: string,
    path: string,
    body?: unknown,
    headers?: Record<string, string>,
  ): Promise<T> {
    return firstValueFrom(
      this.http.request<T>(method, BASE + path, { body, headers }),
    );
  }
  async login(email: string, password: string) {
    const t = await this.request<Tokens>("POST", "/auth/login", {
      email,
      password,
    });
    this.sessionNotice.set(null);
    this.store(t);
  }
  private store(t: Tokens) {
    sessionStorage.setItem("devfest.access", t.accessToken);
    sessionStorage.setItem("devfest.refresh", t.refreshToken);
    this.user.set(t.user);
  }
  refresh(): Promise<Tokens> {
    if (!this.renewing) {
      const token = sessionStorage.getItem("devfest.refresh");
      if (!token) return Promise.reject(new Error("Please sign in again."));
      this.renewing = this.request<Tokens>("POST", "/auth/refresh", {
        refreshToken: token,
      })
        .then((t) => {
          this.store(t);
          return t;
        })
        .catch((e) => {
          if (isFinalSessionEnd(e)) this.endSession(message(e));
          else this.clear();
          throw e;
        })
        .finally(() => (this.renewing = null));
    }
    return this.renewing;
  }
  async restore() {
    if (this.user()) return true;
    if (!sessionStorage.getItem("devfest.refresh")) return false;
    try {
      this.user.set(await this.request<User>("GET", "/auth/me"));
      return true;
    } catch {
      return false;
    }
  }
  async logout() {
    try {
      await this.request("POST", "/auth/logout", {});
    } finally {
      this.clear();
    }
  }
  /**
   * The server ended this session (another device signed in, an organizer signed the team out, …):
   * sign out locally and explain why on the login page. Pending team requests in localStorage are kept.
   */
  endSession(notice: string) {
    if (!this.user() && !sessionStorage.getItem("devfest.refresh")) return;
    this.sessionNotice.set(notice);
    this.clear();
  }
  clear() {
    sessionStorage.removeItem("devfest.access");
    sessionStorage.removeItem("devfest.refresh");
    this.user.set(null);
    void this.router.navigateByUrl("/login");
  }
  async downloadResults() {
    const csv = await firstValueFrom(
      this.http.get(BASE + "/admin/results.csv", { responseType: "text" }),
    );
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "devfest-results.csv";
    a.click();
    URL.revokeObjectURL(url);
  }
}
export function message(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0)
      return "Connection lost. Your local edits are kept; please retry.";
    return e.error?.detail || "The request could not be completed.";
  }
  return e instanceof Error ? e.message : "The request could not be completed.";
}
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(Api);
  if (
    !(req.url === BASE || req.url.startsWith(BASE + "/")) ||
    ["/auth/login", "/auth/refresh"].some((x) => req.url === BASE + x)
  )
    return next(req);
  const token = sessionStorage.getItem("devfest.access");
  return next(
    token
      ? req.clone({ setHeaders: { Authorization: "Bearer " + token } })
      : req,
  ).pipe(
    catchError((e) => {
      if (!(e instanceof HttpErrorResponse) || e.status !== 401)
        return throwError(() => e);
      if (isFinalSessionEnd(e)) {
        api.endSession(message(e));
        return throwError(() => e);
      }
      return from(api.refresh()).pipe(
        switchMap((t) =>
          next(
            req.clone({
              setHeaders: { Authorization: "Bearer " + t.accessToken },
            }),
          ),
        ),
      );
    }),
  );
};
