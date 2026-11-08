import { ChangeDetectionStrategy, Component, computed, linkedSignal, signal } from "@angular/core";
import { RuleData, dataOf } from "./sentinel-data";
import { fireRule } from "./sentinel-logic";
import { SentinelStageBase } from "./stage-base";
import { SentinelStageShell } from "./stage-shell";

/** Stage 9: forward chaining by hand. Clicking a rule whose conditions all hold adds its fact; others refuse. */
@Component({
  selector: "sentinel-stage-rules",
  imports: [SentinelStageShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: "./sentinel-shared.css",
  styles: `
    .facts {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .facts li {
      padding: var(--space-2) var(--space-3);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    .facts li.fresh {
      background: var(--color-success-subtle);
      border: 2px solid var(--color-success);
    }
    .rules {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .rule {
      display: grid;
      gap: var(--space-1);
    }
    .rule .tool {
      inline-size: 100%;
      text-align: start;
    }
    .rule__says {
      margin: 0;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-error);
    }
    .rule__says--ok {
      color: var(--color-text-muted);
    }
    .arrow {
      margin-inline: 0.3em;
    }
    .tag {
      font-family: var(--font-mono);
      font-weight: 700;
      margin-inline-end: var(--space-2);
    }
  `,
  template: `
    <sentinel-stage-shell
      [stage]="stage()"
      [ready]="conclusion() !== null"
      [readOnly]="readOnly()"
      [submitting]="submitting()"
      [error]="error()"
      [hint]="conclusion() === null ? 'Choose the final conclusion.' : null"
      [confirmTitle]="provenConclusion() ? null : 'This conclusion is not in what you know. Submit anyway?'"
      (confirmed)="send()"
    >
      <div class="stage stage--split">
        <div class="panel">
          <h3>What you know</h3>
          <ul class="facts" data-facts aria-live="polite">
            @for (id of known(); track id) {
              <li [class.fresh]="!data().known.includes(id)">{{ factText(id) }}@if (!data().known.includes(id)) { <strong> · new</strong> }</li>
            }
          </ul>
          <div class="btn-row">
            <button type="button" class="tool" data-reset [disabled]="readOnly() || known().length === data().known.length" (click)="reset()">Start again</button>
          </div>
        </div>
        <div class="stage__side">
          <div class="panel">
            <h3>Rules</h3>
            <ul class="rules" data-rules>
              @for (rule of data().rules; track rule.id) {
                <li class="rule">
                  <button type="button" class="tool" [attr.data-rule]="rule.id" [disabled]="readOnly()" (click)="fire(rule.id)">
                    <span class="tag">{{ rule.id }}</span>{{ conditionText(rule.ifAll) }} <span class="arrow" aria-label="gives">→</span> <strong>{{ factText(rule.then) }}</strong>
                  </button>
                  @if (says()[rule.id]; as line) {
                    <p class="rule__says" [class.rule__says--ok]="line !== 'Conditions not met'" role="status">{{ line }}</p>
                  }
                </li>
              }
            </ul>
          </div>
          <fieldset>
            <legend>Final conclusion</legend>
            <div class="opts">
              @for (id of data().conclusions; track id) {
                <label class="opt"><input type="radio" name="rule-conclusion" [checked]="conclusion() === id" [disabled]="readOnly()" (change)="conclusion.set(id)" />{{ factText(id) }}</label>
              }
            </div>
          </fieldset>
        </div>
      </div>
    </sentinel-stage-shell>
  `,
})
export class SentinelStageRules extends SentinelStageBase {
  protected readonly data = computed(() => dataOf<RuleData>(this.stage()));
  protected readonly known = linkedSignal<string, string[]>({
    source: () => this.data().rules.map((r) => r.id).join(","),
    computation: () => [...this.data().known],
  });
  /** What the last click on each rule said. */
  protected readonly says = signal<Record<string, string>>({});
  protected readonly conclusion = signal<string | null>(null);

  /** True while nothing is chosen, or the chosen conclusion is among the known facts. */
  protected readonly provenConclusion = computed(() => this.conclusion() === null || this.known().includes(this.conclusion()!));

  protected factText(id: string): string {
    return this.data().facts.find((f) => f.id === id)?.text ?? id;
  }

  protected conditionText(ids: string[]): string {
    return ids.map((id) => this.factText(id)).join(" + ");
  }

  protected fire(ruleId: string): void {
    const rule = this.data().rules.find((r) => r.id === ruleId);
    if (!rule || this.readOnly()) return;
    const outcome = fireRule(this.known(), rule);
    if (outcome === "added") this.known.update((k) => [...k, rule.then]);
    const line = outcome === "blocked" ? "Conditions not met" : outcome === "known" ? "Nothing new: already known" : "Added to what you know";
    this.says.update((s) => ({ ...s, [ruleId]: line }));
  }

  protected reset(): void {
    this.known.set([...this.data().known]);
    this.says.set({});
  }

  protected send(): void {
    void this.submit({ conclusion: this.conclusion() });
  }
}
