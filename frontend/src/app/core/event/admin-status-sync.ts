import { effect, inject, untracked } from "@angular/core";
import { toSignal } from "@angular/core/rxjs-interop";
import { NavigationEnd, Router } from "@angular/router";
import { filter, map } from "rxjs";
import { EventApi } from "./event-api";
import { EventLiveStore } from "./event-live-store";
import { RevisionSync } from "./revision-sync";

/** Admin pages that fetch GET /admin/board themselves and publish it to the store. */
const SELF_LOADING_PATHS: readonly string[] = ["/event/admin", "/event/admin/overrides"];

function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

/**
 * Keeps the organizer's status bar filled on pages that do not load the admin board (Teams, Content, Results,
 * room boards): GET /admin/board once when nothing is stored, and again only when RevisionSync reports a
 * newer revision than the stored board. It adds no timer of its own. Call it in an injection
 * context (the shell's constructor).
 */
export function keepAdminStatusLoaded(isAdmin: () => boolean): void {
  const router = inject(Router);
  const revisions = inject(RevisionSync);
  const store = inject(EventLiveStore);
  const eventApi = inject(EventApi);
  const path = toSignal(
    router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map((event) => pathOf(event.urlAfterRedirects)),
    ),
    { initialValue: pathOf(router.url) },
  );
  let loading = false;

  effect(() => {
    const revision = revisions.revision();
    if (!isAdmin() || SELF_LOADING_PATHS.includes(path())) return;
    untracked(() => {
      const board = store.adminBoard();
      const stale =
        board === null || (revision !== null && board.state.revision < revision);
      if (!stale || loading) return;
      loading = true;
      eventApi
        .adminBoard()
        .then((fresh) => store.publishAdminBoard(fresh))
        .catch(() => {
          // The bar keeps its last value; the next revision change tries again.
        })
        .finally(() => (loading = false));
    });
  });
}
