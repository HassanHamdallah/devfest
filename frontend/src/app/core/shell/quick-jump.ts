import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from "@angular/core";
import { Router } from "@angular/router";
import { UiRoomChip } from "../../shared/ui/ui-room-chip/ui-room-chip";
import { RoomCode } from "../event/event-models";
import { EventLiveStore } from "../event/event-live-store";
import { ADMIN_PAGES } from "./event-nav";
import { NavIcon } from "./nav-icon";
import { TeamDrawerService } from "./team-drawer.service";

interface JumpItem {
  key: string;
  label: string;
  hint: string;
  roomCode?: RoomCode;
  go: () => void;
}

const MAX_RESULTS = 12;

/**
 * Ctrl+K / ⌘K for organizers: filters pages, the four rooms (opens the room board) and every team (opens the
 * team drawer) on the client, from data already loaded. Arrow keys move, Enter opens, Escape closes.
 */
@Component({
  selector: "app-quick-jump",
  imports: [NavIcon, UiRoomChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: "./quick-jump.html",
  styleUrl: "./quick-jump.css",
})
export class QuickJump {
  private readonly router = inject(Router);
  private readonly store = inject(EventLiveStore);
  private readonly drawer = inject(TeamDrawerService);

  readonly open = input(false);
  readonly closed = output<void>();

  protected readonly query = signal("");
  protected readonly active = signal(0);
  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>("dialogEl");
  private readonly inputRef = viewChild.required<ElementRef<HTMLInputElement>>("searchEl");

  private readonly items = computed<JumpItem[]>(() => {
    const organization = this.store.organization();
    const pages = ADMIN_PAGES.map((page) => ({
      key: `page:${page.path}`,
      label: page.label,
      hint: "Page",
      go: () => void this.router.navigateByUrl(page.path),
    }));
    const rooms = (organization?.rooms ?? []).map((room) => ({
      key: `room:${room.id}`,
      label: `${room.name} room`,
      hint: "Room board",
      roomCode: room.code,
      go: () => void this.router.navigateByUrl(`/event/room/${room.id}`),
    }));
    const teams = (organization?.teams ?? []).map((team) => ({
      key: `team:${team.id}`,
      label: team.name,
      hint: `Team · group ${team.groupCode}`,
      go: () => this.drawer.open(team.id),
    }));
    return [...pages, ...rooms, ...teams];
  });

  protected readonly results = computed(() => {
    const text = this.query().trim().toLowerCase();
    const matches = text
      ? this.items().filter((item) =>
          `${item.label} ${item.hint}`.toLowerCase().includes(text),
        )
      : this.items();
    return matches.slice(0, MAX_RESULTS);
  });
  protected readonly activeId = computed(() =>
    this.results().length ? `quick-jump-${this.active()}` : null,
  );

  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialogRef().nativeElement;
      if (this.open() && !dialog.open) {
        this.query.set("");
        this.active.set(0);
        dialog.showModal();
        this.inputRef().nativeElement.focus();
      } else if (!this.open() && dialog.open) {
        dialog.close();
      }
    });
  }

  protected search(value: string): void {
    this.query.set(value);
    this.active.set(0);
  }

  protected handleKey(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === "ArrowDown" && count) {
      event.preventDefault();
      this.active.update((index) => (index + 1) % count);
    } else if (event.key === "ArrowUp" && count) {
      event.preventDefault();
      this.active.update((index) => (index - 1 + count) % count);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const item = this.results()[this.active()];
      if (item) this.choose(item);
    }
  }

  protected choose(item: JumpItem): void {
    this.closed.emit();
    item.go();
  }

  protected handleCancel(event: Event): void {
    event.preventDefault();
    this.closed.emit();
  }

  protected handleBackdrop(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) this.closed.emit();
  }
}
