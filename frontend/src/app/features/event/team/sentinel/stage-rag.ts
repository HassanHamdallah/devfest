import { ChangeDetectionStrategy, Component, computed, signal } from "@angular/core";
import { RagData, dataOf, runOf } from "./sentinel-data";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 10: ask ECHO with a chunk size and a top-k (three different tries), then lock one setting. */
@Component({
  selector: "sentinel-stage-rag",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .question {
      margin: 0;
      font-size: var(--font-size-lg, 1.125rem);
      font-weight: var(--font-weight-semibold);
    }
    .opts--row {
      grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
    }
    .asked {
      display: grid;
      gap: var(--space-3);
      padding: var(--space-3) var(--space-4);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .asked h4 {
      margin: 0;
    }
    .chunk {
      margin: 0;
      padding: var(--space-2) var(--space-3);
      font-size: var(--font-size-sm);
      background: var(--color-surface-subtle);
      border-inline-start: 4px solid var(--color-border-strong);
      border-radius: var(--radius-sm);
    }
    .reply {
      margin: 0;
      padding: var(--space-2) var(--space-3);
      background: var(--room-tint);
      border-radius: var(--radius-md);
    }
    .asks {
      display: grid;
      gap: var(--space-3);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="lock() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      submitLabel="Lock this setting"
      [hint]="lock() === null ? 'Choose the setting to lock.' : null"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div class="stage__side">
          <p class="question" data-question>“{{ data().question }}”</p>
          <fieldset>
            <legend>Chunk size</legend>
            <div class="opts opts--row">
              @for (size of data().chunkSizes; track size) {
                <label class="opt"><input type="radio" name="rag-size" [checked]="size === chunkSize()" [disabled]="readOnly()" (change)="chunkSize.set(size)" />{{ size }}</label>
              }
            </div>
          </fieldset>
          <fieldset>
            <legend>Top-k (pieces ECHO reads)</legend>
            <div class="opts opts--row">
              @for (k of data().topKs; track k) {
                <label class="opt"><input type="radio" name="rag-k" [checked]="k === topK()" [disabled]="readOnly()" (change)="topK.set(k)" />Top-{{ k }}</label>
              }
            </div>
          </fieldset>
          <div class="btn-row">
            <button type="button" class="tool tool--primary" data-ask [disabled]="current() === null || readOnly() || running() !== null || (!asked() && stage().runsLeft === 0)" (click)="ask()">Ask ECHO</button>
            <span class="event-muted" data-runs-left>Tries left: {{ stage().runsLeft }} of {{ data().runsAllowed }}</span>
          </div>
          <fieldset>
            <legend>Setting to lock</legend>
            <div class="opts">
              @for (config of data().configs; track config.id) {
                <label class="opt"><input type="radio" name="rag-lock" [checked]="lock() === config.id" [disabled]="readOnly()" (change)="lock.set(config.id)" />{{ config.chunkSize }} chunks · top-{{ config.topK }}</label>
              }
            </div>
          </fieldset>
        </div>
        <div class="stage__side">
          <div class="asks" data-asks aria-live="polite">
            @for (run of stage().runs; track run.config) {
              <section class="asked" [attr.data-run]="run.config">
                <h4>{{ label(run.config) }}</h4>
                @for (chunk of chunks(run.result); track $index) {
                  <p class="chunk">{{ chunk }}</p>
                }
                <p class="reply"><strong>ECHO:</strong> {{ run.result['reply'] }}</p>
              </section>
            } @empty {
              <p class="event-muted">Nothing asked yet. ECHO shows what it retrieved and how it answers.</p>
            }
          </div>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageRag extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<RagData>(this.stage()));
  protected readonly chunkSize = signal<string | null>(null);
  protected readonly topK = signal<number | null>(null);
  protected readonly lock = signal<string | null>(null);
  protected readonly current = computed(
    () => this.data().configs.find((c) => c.chunkSize === this.chunkSize() && c.topK === this.topK())?.id ?? null,
  );
  protected readonly asked = computed(() => {
    const id = this.current();
    return id !== null && runOf(this.stage(), id) !== null;
  });

  protected label(id: string): string {
    const config = this.data().configs.find((c) => c.id === id);
    return config ? `${config.chunkSize} chunks · top-${config.topK}` : id;
  }

  protected chunks(result: Record<string, unknown>): string[] {
    return (result["chunks"] as string[]) ?? [];
  }

  protected ask(): void {
    const id = this.current();
    if (id) void this.run(id);
  }

  protected send(): void {
    const config = this.lock();
    if (config) void this.submit({ config });
  }
}
