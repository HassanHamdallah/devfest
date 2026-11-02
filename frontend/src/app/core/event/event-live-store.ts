import { Injectable, computed, inject, signal } from "@angular/core";
import {
  AdminBoard,
  Organization,
  RoomBoard,
  RoomCode,
  RoundClock,
} from "./event-models";
import { FinalStatus, finalStatus } from "./event-status";
import { RevisionPoller } from "./revision-poller";

/** A round clock and the local time it arrived, so a later reader can still count down correctly. */
export interface ReceivedClock {
  clock: RoundClock;
  receivedAt: number;
  revision: number;
}

/**
 * The last event data each page already fetched, shared with the shell (status bar, sidebar badges, quick
 * jump, team drawer). Pages publish what they loaded; nothing here fetches or polls. Values are kept only
 * in memory for this tab.
 */
@Injectable({ providedIn: "root" })
export class EventLiveStore {
  private readonly poller = inject(RevisionPoller);

  readonly adminBoard = signal<AdminBoard | null>(null);
  readonly organization = signal<Organization | null>(null);
  readonly clock = signal<ReceivedClock | null>(null);
  /** Manager boards: the final state as far as a room can tell (content locks once the final opens). */
  readonly roomFinalOpened = signal<boolean | null>(null);

  /** True when the poller has seen a newer revision than the data behind the status bar. */
  readonly stale = computed(() => {
    const latest = this.poller.revision();
    const clock = this.clock();
    return clock !== null && latest !== null && latest > clock.revision;
  });
  readonly finalStatus = computed<FinalStatus | null>(() => {
    const board = this.adminBoard();
    if (board) return finalStatus(board.state);
    const opened = this.roomFinalOpened();
    if (opened === null) return null;
    return opened
      ? { label: "Final opened", variant: "accent" }
      : { label: "Final not opened", variant: "neutral" };
  });
  readonly resultsPublished = computed(
    () => this.adminBoard()?.state.resultsPublished ?? null,
  );
  readonly alertCount = computed(() => this.adminBoard()?.alerts.length ?? 0);
  /** Submissions waiting for a decision, per room code. */
  readonly pendingByRoom = computed(() => {
    const counts = new Map<RoomCode, number>();
    for (const room of this.adminBoard()?.rooms ?? [])
      counts.set(room.code, room.pending);
    return counts;
  });

  publishAdminBoard(board: AdminBoard): void {
    this.adminBoard.set(board);
    this.setClock(board.state, board.state.revision);
  }

  publishRoomBoard(board: RoomBoard): void {
    this.roomFinalOpened.set(!board.room.contentEditable);
    this.setClock(board.clock, board.revision);
  }

  publishOrganization(organization: Organization): void {
    this.organization.set(organization);
  }

  /** Signing out forgets everything, so the next account starts clean. */
  clear(): void {
    this.adminBoard.set(null);
    this.organization.set(null);
    this.clock.set(null);
    this.roomFinalOpened.set(null);
  }

  private setClock(clock: RoundClock, revision: number): void {
    this.clock.set({ clock, receivedAt: Date.now(), revision });
  }
}
