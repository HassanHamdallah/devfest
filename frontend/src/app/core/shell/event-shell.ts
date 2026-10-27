import { NgTemplateOutlet } from "@angular/common";
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
import { AppLogo } from "../../shared/ui/app-logo/app-logo";
import { BrandBar } from "../../shared/ui/brand-bar/brand-bar";
import { ToastHost } from "../../shared/ui/toast/toast-host";
import { ToastService } from "../../shared/ui/toast/toast.service";
import { UiButton } from "../../shared/ui/ui-button/ui-button";
import { UiDrawer } from "../../shared/ui/ui-drawer/ui-drawer";
import { Api } from "../api";
import { User } from "../models";
import { keepAdminStatusLoaded } from "../event/admin-status-sync";
import { EventApi } from "../event/event-api";
import { EventLiveStore } from "../event/event-live-store";
import { ROOM_CODES } from "../event/event-models";
import { RevisionSync } from "../event/revision-sync";
import { RoleHome } from "../role-home";
import { EventStatusBar } from "./event-status-bar";
import {
  EventNavLink,
  ROLE_LABELS,
  eventHomePath,
  eventNavGroups,
} from "./event-nav";
import { NavIcon } from "./nav-icon";
import { QuickJump } from "./quick-jump";
import { TeamDrawer } from "./team-drawer";
import { TeamDrawerService } from "./team-drawer.service";

const COLLAPSED_KEY = "devfest.sidebar-collapsed";

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * Shell for ADMIN, MANAGER and MEMBER: sidebar with the GDG logo and grouped navigation (collapsible to icons
 * on laptops), a top bar with a menu drawer on phones, the sticky event status for organizers and managers,
 * toasts, and for organizers the quick jump (Ctrl+K) and the team drawer (both loaded on first use).
 */
@Component({
  selector: "app-event-shell",
  imports: [
    AppLogo,
    BrandBar,
    EventStatusBar,
    NavIcon,
    NgTemplateOutlet,
    QuickJump,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TeamDrawer,
    ToastHost,
    UiButton,
    UiDrawer,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "(document:keydown)": "handleShortcut($event)" },
  templateUrl: "./event-shell.html",
  styleUrl: "./event-shell.css",
})
export class EventShell {
  private readonly api = inject(Api);
  private readonly eventApi = inject(EventApi);
  private readonly roleHome = inject(RoleHome);
  private readonly revisions = inject(RevisionSync);
  private readonly store = inject(EventLiveStore);
  protected readonly teamDrawer = inject(TeamDrawerService);
  /** The toast host is loaded with the first toast. */
  protected readonly toasts = inject(ToastService).toasts;

  readonly user = input.required<User>();

  protected readonly menuOpen = signal(false);
  protected readonly collapsed = signal(readCollapsed());
  protected readonly quickJumpOpen = signal(false);
  private readonly managerRoomPath = signal("/event/room");
  private loadingNavigation = false;

  protected readonly isAdmin = computed(() => this.user().role === "ADMIN");
  protected readonly showStatus = computed(() => this.user().role !== "MEMBER");
  protected readonly roleLabel = computed(() => ROLE_LABELS[this.user().role]);
  protected readonly homePath = computed(() =>
    eventHomePath(this.user().role, this.managerRoomPath()),
  );
  private readonly adminRooms = computed(() =>
    [...(this.store.organization()?.rooms ?? [])]
      .sort((a, b) => ROOM_CODES.indexOf(a.code) - ROOM_CODES.indexOf(b.code))
      .map((room) => ({ id: room.id, code: room.code, name: room.name })),
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
      .subscribe(() => {
        this.menuOpen.set(false);
        this.teamDrawer.close();
      });
    keepAdminStatusLoaded(() => this.isAdmin());
    // Runs once on start and again on each revision change, so a later room assignment shows up.
    effect(() => {
      this.revisions.revision();
      untracked(() => void this.loadNavigation());
    });
  }

  /** Sidebar badge: active alerts next to Control, pending reviews next to each room. */
  protected badge(link: EventNavLink): { count: number; label: string } | null {
    const count = link.alerts
      ? this.store.alertCount()
      : link.roomCode
        ? (this.store.pendingByRoom().get(link.roomCode) ?? 0)
        : 0;
    if (count <= 0) return null;
    const label = link.alerts
      ? count === 1 ? "active alert" : "active alerts"
      : "pending review";
    return { count, label };
  }

  protected toggleCollapsed(): void {
    this.collapsed.update((value) => !value);
    try {
      localStorage.setItem(COLLAPSED_KEY, this.collapsed() ? "1" : "0");
    } catch {
      // Remembering the choice is a convenience only.
    }
  }

  /** Ctrl+K / ⌘K opens the quick jump for organizers; there are no single-key shortcuts. */
  protected handleShortcut(event: KeyboardEvent): void {
    if (!this.isAdmin() || event.key.toLowerCase() !== "k") return;
    if (!(event.ctrlKey || event.metaKey) || event.altKey) return;
    event.preventDefault();
    this.quickJumpOpen.set(true);
  }

  protected signOut(): void {
    this.store.clear();
    void this.api.logout();
  }

  private async loadNavigation(): Promise<void> {
    if (this.loadingNavigation) return;
    this.loadingNavigation = true;
    try {
      const role = this.user().role;
      if (role === "MANAGER")
        this.managerRoomPath.set(await this.roleHome.managerRoomUrl());
      if (role === "ADMIN" && !this.store.organization())
        await this.loadOrganization();
    } finally {
      this.loadingNavigation = false;
    }
  }

  private async loadOrganization(): Promise<void> {
    try {
      this.store.publishOrganization(await this.eventApi.organization());
    } catch {
      // The room links stay hidden until a later revision change retries.
    }
  }
}
