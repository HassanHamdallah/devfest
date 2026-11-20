import { ChangeDetectionStrategy, Component, inject, input } from "@angular/core";
import { FinalBoardRow, FinalPhase, RecoveryPathCode } from "../../../../core/event/event-models";

const PATH_LABELS: Record<RecoveryPathCode, string> = { NONE: "None", ML: "ML", ML_ARDUINO: "ML + Arduino" };
import { FINAL_STAGE_LABELS, formatAdjustedSeconds, formatClock, formatPoints, formatTime } from "../../../../core/event/format";
import { TeamDrawerService } from "../../../../core/shell/team-drawer.service";

/**
 * The live final table: every team's status and stage, its recovery (the keys it held, its path, the level it is on, when
 * it finished and its crypto window on the final's clock), its attempts, the evidence files it decrypted (0 to 4), its
 * suspect and the parts of its final score. Organizers only: it shows correctness that a team never sees.
 */
@Component({
  selector: "app-final-table",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="event-table-wrap">
    <table data-final-table>
      <caption>
        Final
      </caption>
      <thead>
        <tr>
          <th scope="col">Team</th>
          <th scope="col">Status</th>
          <th scope="col">Stage</th>
          <th scope="col">Room clues</th>
          <th scope="col">Keys held</th>
          <th scope="col">Recovery path</th>
          <th scope="col">Recovery level</th>
          <th scope="col">Recovery finished</th>
          <th scope="col">Crypto window</th>
          <th scope="col">Clues (0-4)</th>
          <th scope="col">Unlock tries</th>
          <th scope="col">Passphrase tries</th>
          <th scope="col">Recovery checks</th>
          <th scope="col">Suspect</th>
          <th scope="col">Active time</th>
          <th scope="col">Finished</th>
          <th scope="col">Crypto</th>
          <th scope="col">Suspect pts</th>
          <th scope="col">Time</th>
          <th scope="col">Total</th>
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.teamId) {
          <tr [attr.data-team]="row.teamName" [attr.data-stage]="row.stage">
            <th scope="row">
              <button type="button" class="event-link-button" (click)="drawer.open(row.teamId)">
                {{ row.teamName }}<span class="event-sr-only">: show details</span>
              </button>
            </th>
            <td data-status>{{ statusLabel(row) }}</td>
            <td data-stage-cell>{{ row.stage }}@if (row.phase) { · {{ phaseLabel(row.phase) }}}</td>
            <td class="event-num">{{ row.clues }}</td>
            <td class="event-num" data-keys-held>{{ row.keysHeld }}</td>
            <td data-recovery-path>{{ pathLabel(row) }}</td>
            <td data-recovery-level>{{ levelLabel(row) }}</td>
            <td class="event-num" data-recovery-finished>{{ recoveryFinished(row) }}</td>
            <td class="event-num" data-crypto-window>{{ cryptoWindow(row) }}</td>
            <td class="event-num" data-clues-solved>{{ row.cluesSolved }}</td>
            <td class="event-num">{{ row.unlockAttempts }} ({{ row.unlockWrong }} wrong)</td>
            <td class="event-num">{{ row.passphraseAttempts }} ({{ row.passphraseWrong }} wrong)</td>
            <td class="event-num">{{ row.recoveryChecks }}</td>
            <td data-suspect>{{ suspect(row) }}</td>
            <td class="event-num">{{ active(row) }}</td>
            <td class="event-num">{{ finished(row) }}</td>
            <td class="event-num">{{ points(row.cluePoints) }}</td>
            <td class="event-num">{{ points(row.suspectPoints) }}</td>
            <td class="event-num">{{ points(row.timePoints) }}</td>
            <td class="event-num total" data-final-score>{{ points(row.finalScore) }}</td>
          </tr>
        } @empty {
          <tr>
            <td colspan="20" class="muted">No active teams yet.</td>
          </tr>
        }
      </tbody>
    </table>
  </div>`,
  styles: `
    th[scope="row"] {
      font-size: inherit;
      letter-spacing: normal;
      color: var(--color-text);
      background: none;
    }
    td,
    th {
      white-space: nowrap;
    }
    .total {
      font-weight: var(--font-weight-bold);
    }
    .muted {
      color: var(--color-text-muted);
    }
  `,
})
export class FinalTable {
  protected readonly drawer = inject(TeamDrawerService);
  readonly rows = input.required<readonly FinalBoardRow[]>();

  protected phaseLabel(phase: FinalPhase): string {
    return phase === "DECRYPTION" ? "Decryption" : "Investigation";
  }

  protected statusLabel(row: FinalBoardRow): string {
    return FINAL_STAGE_LABELS[row.status] + (row.disqualified ? " (disqualified)" : "");
  }

  protected pathLabel(row: FinalBoardRow): string {
    return PATH_LABELS[row.recoveryPath];
  }

  /** "ML · stage 3 of 6", "Arduino", "Done" once finished, "—" when the team needs no recovery. */
  protected levelLabel(row: FinalBoardRow): string {
    if (row.recoveryPath === "NONE") return "—";
    if (row.recoveryLevel === null) return "Done";
    return row.recoveryLevel === "ML" ? `ML · stage ${row.recoveryStage} of 6` : "Arduino";
  }

  /** The active clock at which the recovery was finished ("12:30"); "—" while it is not, and for a team that needs none. */
  protected recoveryFinished(row: FinalBoardRow): string {
    return row.recoveryPath === "NONE" ? "—" : formatClock(row.recoveryFinishedAtSeconds);
  }

  /**
   * "from–to" on the final's clock ("12:30–19:10"): from the team's start until it decrypted its fourth file, "from–end"
   * while it has not (it keeps Phase 1 until the final ends); "—" until the recovery is finished.
   */
  protected cryptoWindow(row: FinalBoardRow): string {
    if (row.cryptoFromSeconds === null) return "—";
    return `${formatClock(row.cryptoFromSeconds)}–${row.cryptoToSeconds === null ? "end" : formatClock(row.cryptoToSeconds)}`;
  }

  protected suspect(row: FinalBoardRow): string {
    return row.suspect === null ? "—" : `${row.suspect} (${row.suspectCorrect ? "correct" : "wrong"})`;
  }

  protected active(row: FinalBoardRow): string {
    return formatAdjustedSeconds(row.activeSeconds);
  }

  protected finished(row: FinalBoardRow): string {
    if (row.finishedAt === null) return row.finishedBy === "TIME_UP" ? "Time up" : row.finishedBy === "CLOSED" ? "Closed" : "—";
    return formatTime(row.finishedAt);
  }

  protected points(value: number): string {
    return formatPoints(value);
  }
}
