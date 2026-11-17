import { HttpErrorResponse } from "@angular/common/http";
import { ChangeDetectionStrategy, Component, computed, inject, signal } from "@angular/core";
import { message } from "../../../../core/api";
import { EventApi } from "../../../../core/event/event-api";
import { UiAlert } from "../../../../shared/ui/ui-alert/ui-alert";
import { UiCard } from "../../../../shared/ui/ui-card/ui-card";
import { UiConfirmDialog } from "../../../../shared/ui/ui-confirm-dialog/ui-confirm-dialog";
import { UiStatusChip } from "../../../../shared/ui/ui-status-chip/ui-status-chip";
import { EventPackageUpload, PackageSummary, formatBytes } from "../../manager/package-upload";
import { RemoteData } from "../shared/remote-data";

/** The server's own upload limit for a terminal package (application.properties). */
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

function problemsOf(error: unknown): string[] {
  const problems: unknown = error instanceof HttpErrorResponse ? error.error?.problems : null;
  return Array.isArray(problems) ? problems.filter((p): p is string => typeof p === "string") : [];
}

/**
 * The final's own terminal package (docs/final/last-shipment/final-package.zip): kept apart from every room's
 * package, uploaded, replaced or removed by the admin until the final opens. Teams download it only on the case
 * screen.
 */
@Component({
  selector: "app-final-package-section",
  imports: [EventPackageUpload, UiAlert, UiCard, UiConfirmDialog, UiStatusChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .stack {
      display: grid;
      gap: var(--space-3);
    }
    p {
      margin: 0;
    }
  `,
  template: `<ui-card data-final-package>
    <div ui-card-header class="event-inline">
      <h2>Final terminal package</h2>
      @if (view(); as v) {
        @if (v.uploaded) {
          <ui-status-chip variant="success">Uploaded</ui-status-chip>
        } @else {
          <ui-status-chip variant="warning">Not uploaded</ui-status-chip>
        }
        @if (v.readOnly) {
          <ui-status-chip variant="warning">Read-only</ui-status-chip>
        }
      }
    </div>
    <div class="stack">
      <p class="event-muted">
        The ZIP every team's case terminal installs (built by docs/final/last-shipment/build-final-package.sh). It is
        separate from the rooms' packages. Up to {{ limit }} uploaded, 5000 entries, 20 MB unpacked.
      </p>
      @if (view(); as v) {
        @if (lastFile(); as name) {
          <p class="event-muted" data-final-package-file>File: {{ name }}</p>
        }
        <event-package-upload
          [summary]="summary()"
          [editable]="!v.readOnly"
          [busy]="busy()"
          [problems]="problems()"
          emptyText="No final terminal package yet. Teams see “The terminal package is not ready yet.”"
          (chosen)="upload($event)"
          (remove)="removing.set(true)"
        />
        @if (v.readOnly) {
          <ui-alert variant="info">Event content is read-only after the final opens.</ui-alert>
        }
      }
      @if (error(); as text) {
        <ui-alert variant="error">{{ text }}</ui-alert>
      }
      @if (notice(); as text) {
        <ui-alert variant="success" data-final-package-notice>{{ text }}</ui-alert>
      }
    </div>
    <ui-confirm-dialog
      [open]="removing()"
      title="Remove the final terminal package?"
      message="Teams on the case screen will see that the terminal package is not ready until a new one is uploaded."
      confirmVariant="danger"
      [busy]="busy()"
      (confirmed)="remove()"
      (cancelled)="removing.set(false)"
    />
  </ui-card>`,
})
export class FinalPackageSection {
  private readonly eventApi = inject(EventApi);

  protected readonly data = new RemoteData(() => this.eventApi.finalTerminalPackage());
  protected readonly view = this.data.data;
  protected readonly summary = computed<PackageSummary | null>(() => {
    const v = this.view();
    if (!v?.uploaded || v.sha256 === null) return null;
    return {
      sha256: v.sha256,
      fileCount: v.fileCount ?? 0,
      unpackedBytes: v.unpackedBytes ?? 0,
      archiveBytes: v.archiveBytes ?? 0,
      owners: v.owners,
      uploadedBy: v.uploadedBy ?? "",
      uploadedAt: v.uploadedAt ?? "",
    };
  });
  protected readonly limit = formatBytes(MAX_UPLOAD_BYTES);
  protected readonly busy = signal(false);
  protected readonly removing = signal(false);
  protected readonly problems = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  /** The name of the file uploaded in this session (the server keeps only the built bundle). */
  protected readonly lastFile = signal<string | null>(null);

  constructor() {
    void this.data.load();
  }

  protected async upload(file: File): Promise<void> {
    if (this.busy()) return;
    if (file.size > MAX_UPLOAD_BYTES) {
      this.error.set(`${file.name} is larger than ${this.limit}.`);
      return;
    }
    const replacing = this.view()?.uploaded === true;
    await this.run(async () => {
      await this.eventApi.uploadFinalTerminalPackage(file);
      await this.data.load();
      this.lastFile.set(file.name);
      this.notice.set(`${replacing ? "Replaced" : "Uploaded"} the final terminal package (${file.name}).`);
    });
  }

  protected async remove(): Promise<void> {
    await this.run(async () => {
      await this.eventApi.deleteFinalTerminalPackage();
      await this.data.load();
      this.lastFile.set(null);
      this.notice.set("Removed the final terminal package.");
    });
    this.removing.set(false);
  }

  private async run(work: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.problems.set([]);
    try {
      await work();
    } catch (e) {
      this.problems.set(problemsOf(e));
      if (this.problems().length === 0) this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}
