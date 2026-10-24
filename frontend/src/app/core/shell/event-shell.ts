import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from "@angular/router";
import { filter } from "rxjs";
import { Api } from "../api";
import { User } from "../models";
import { EventApi } from "../event/event-api";
import { ROOM_CODES } from "../event/event-models";
import { RevisionPoller } from "../event/revision-poller";
import { RoleHome } from "../role-home";
import { UiButton } from "../../shared/ui/ui-button/ui-button";
import {
  EventNavRoom,
  ROLE_LABELS,
  eventHomePath,
  eventNavGroups,
} from "./event-nav";

/** Sidebar shell for ADMIN, MANAGER and MEMBER. On phones the navigation collapses behind "Menu". */
@Component({
  selector: "app-event-shell",
  imports: [RouterOutlet, RouterLink, RouterLinkActive, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: "display: block" },
  template: `<div
    class="event-shell"
    [class.event-shell--menu-open]="menuOpen()"
  >
    <aside class="event-sidebar">
      <div class="event-sidebar__top">
        <a class="event-brand" [routerLink]="homePath()"
          >DevFest · Investigation</a
        >
        <button
          type="button"
          class="event-menu-toggle"
          aria-controls="event-nav"
          [attr.aria-expanded]="menuOpen()"
          (click)="toggleMenu()"
        >
          Menu
        </button>
      </div>
      <nav id="event-nav" class="event-nav" aria-label="Event navigation">
        @for (group of navGroups(); track group.heading) {
          @if (group.heading) {
            <p class="event-nav__heading">{{ group.heading }}</p>
          }
          @for (link of group.links; track link.path) {
            <a
              [routerLink]="link.path"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              [routerLinkActiveOptions]="{ exact: link.exact }"
              >{{ link.label }}</a
            >
          }
        }
      </nav>
      <div class="event-user">
        <span class="event-user__name">{{ user().name }}</span>
        <span class="event-user__role">{{ roleLabel() }}</span>
        <ui-button variant="secondary" size="sm" (click)="signOut()"
          >Sign out</ui-button
        >
      </div>
    </aside>
    <main class="event-main"><router-outlet /></main>
  </div>`,
})
export class EventShell {
  private readonly api = inject(Api);
  private readonly eventApi = inject(EventApi);
  private readonly roleHome = inject(RoleHome);
  private readonly poller = inject(RevisionPoller);

  readonly user = input.required<User>();

  protected readonly menuOpen = signal(false);
  private readonly managerRoomPath = signal("/event/room");
  private readonly adminRooms = signal<EventNavRoom[]>([]);
  private loadingNavigation = false;

  protected readonly roleLabel = computed(() => ROLE_LABELS[this.user().role]);
  protected readonly homePath = computed(() =>
    eventHomePath(this.user().role, this.managerRoomPath()),
  );
  protected readonly navGroups = computed(() =>
    eventNavGroups(this.user().role, this.managerRoomPath(), this.adminRooms()),
  );

  constructor() {
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.menuOpen.set(false));
    // Runs once on start and again on each revision change, so a later room assignment shows up.
    effect(() => {
      this.poller.revision();
      untracked(() => void this.loadNavigation());
    });
  }

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected signOut(): void {
    void this.api.logout();
  }

  private async loadNavigation(): Promise<void> {
    if (this.loadingNavigation) return;
    this.loadingNavigation = true;
    try {
      const role = this.user().role;
      if (role === "MANAGER")
        this.managerRoomPath.set(await this.roleHome.managerRoomUrl());
      if (role === "ADMIN" && this.adminRooms().length === 0)
        await this.loadAdminRooms();
    } finally {
      this.loadingNavigation = false;
    }
  }

  private async loadAdminRooms(): Promise<void> {
    try {
      const { rooms } = await this.eventApi.organization();
      const byPosition = [...rooms].sort(
        (a, b) => ROOM_CODES.indexOf(a.code) - ROOM_CODES.indexOf(b.code),
      );
      this.adminRooms.set(
        byPosition.map((room) => ({ id: room.id, name: room.name })),
      );
    } catch {
      // The room links stay hidden until a later revision change retries.
    }
  }
}
