import { ChangeDetectionStrategy, Component, input, output } from "@angular/core";
import { formatTime } from "../../../core/event/format";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiButton } from "../../../shared/ui/ui-button/ui-button";

/** What the upload shows about a stored package (a room's or the final's). */
export interface PackageSummary {
  sha256: string;
  fileCount: number;
  unpackedBytes: number;
  archiveBytes: number;
  owners: string[];
  uploadedBy: string;
  uploadedAt: string;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/**
 * A terminal server package: its summary, "Upload / Replace package (.zip)", "Remove package" and the list of every
 * problem of a refused ZIP. Used for a room's package (the room board) and for the final's own package (the admin's
 * Content page). The parent sends the file and confirms the removal; extra summary rows and controls (the room's
 * terminal clock) are projected with `package-row` and as content.
 */
@Component({
  selector: "event-package-upload",
  imports: [UiAlert, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: grid;
      gap: var(--space-3);
    }
    .terminal-files__summary {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
      gap: var(--space-2) var(--space-4);
      margin: 0;
    }
    /* ::ng-deep reaches the summary rows the parent projects (the room's terminal clock). */
    :host ::ng-deep .terminal-files__summary div {
      min-inline-size: 0;
    }
    :host ::ng-deep .terminal-files__summary dt {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    :host ::ng-deep .terminal-files__summary dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .terminal-files__empty {
      margin: 0;
    }
    .terminal-files__pick {
      display: inline-grid;
      gap: var(--space-1);
      font-size: var(--font-size-sm);
    }
    .terminal-files__problems {
      margin: var(--space-2) 0 0;
      padding-inline-start: var(--space-5);
      overflow-wrap: anywhere;
    }
  `,
  template: `@if (summary(); as pack) {
      <dl class="terminal-files__summary">
        <div><dt>Files</dt><dd class="event-num">{{ pack.fileCount }} · {{ bytes(pack.unpackedBytes) }} unpacked</dd></div>
        <div><dt>Archive</dt><dd class="event-num">{{ bytes(pack.archiveBytes) }}</dd></div>
        <div><dt>Owners</dt><dd>{{ pack.owners.length ? pack.owners.join(", ") : "root only" }}</dd></div>
        <div><dt>Uploaded</dt><dd>{{ pack.uploadedBy }} · {{ time(pack.uploadedAt) }}</dd></div>
        <div><dt>Fingerprint</dt><dd><code data-package-sha>{{ pack.sha256.slice(0, 12) }}</code></dd></div>
        <ng-content select="[package-row]" />
      </dl>
      <ng-content />
    } @else {
      <p class="terminal-files__empty">{{ emptyText() }}</p>
    }
    @if (editable()) {
      <div class="event-inline">
        <label class="terminal-files__pick">
          <span class="terminal-files__pick-label">{{ summary() ? "Replace package (.zip)" : "Upload package (.zip)" }}</span>
          <input type="file" accept=".zip,application/zip" [disabled]="busy()" (change)="pick($event)" />
        </label>
        @if (summary()) {
          <ui-button variant="ghost" size="sm" [disabled]="busy()" (click)="remove.emit()">Remove package</ui-button>
        }
      </div>
    }
    @if (problems().length) {
      <ui-alert variant="error">
        <strong>The package was not accepted. Fix these and upload it again:</strong>
        <ul class="terminal-files__problems">
          @for (problem of problems(); track $index) {
            <li>{{ problem }}</li>
          }
        </ul>
      </ui-alert>
    }`,
})
export class EventPackageUpload {
  readonly summary = input<PackageSummary | null>(null);
  /** False once the final has opened (content is read-only). */
  readonly editable = input(true);
  readonly busy = input(false);
  /** Every rule a refused package broke, as the server listed them. */
  readonly problems = input<readonly string[]>([]);
  readonly emptyText = input("No server package yet.");
  /** The ZIP the organizer chose (the parent checks its size and sends it). */
  readonly chosen = output<File>();
  readonly remove = output<void>();

  protected readonly bytes = formatBytes;
  protected readonly time = formatTime;

  protected pick(event: Event): void {
    const picker = event.target as HTMLInputElement;
    const file = picker.files?.[0];
    picker.value = "";
    if (file) this.chosen.emit(file);
  }
}
