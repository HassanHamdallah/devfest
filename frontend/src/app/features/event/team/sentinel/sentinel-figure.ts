import { ChangeDetectionStrategy, Component, computed, input } from "@angular/core";
import { SentinelFigureData } from "./sentinel-data";

let nextFigure = 0;

/** A number as the brief writes it. */
function show(value: number): string {
  return String(value);
}

interface Pt {
  x: number;
  y: number;
}

/**
 * The drawing above a CHOICE or INPUT stage's brief (rules §19.2): inline SVG that scales to the width, in the site's
 * colour tokens, with a text alternative and a short caption. It draws the question's own data and nothing else: the
 * server sends no answer and no intermediate result, so none can be shown. Question marks stand for what the team
 * has to work out.
 */
@Component({
  selector: "sentinel-figure",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { "[attr.data-figure]": "figure().type" },
  styles: `
    :host {
      display: block;
      max-inline-size: 560px;
      margin: 0;
    }
    figure {
      display: grid;
      gap: var(--space-2);
      margin: 0;
      padding: var(--space-3);
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-md);
    }
    svg {
      display: block;
      inline-size: 100%;
      block-size: auto;
    }
    figcaption {
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    .t {
      font-size: 15px;
      fill: var(--color-text);
    }
    .t--small {
      font-size: 13px;
    }
    .t--muted {
      fill: var(--color-text-muted);
    }
    .t--bold {
      font-weight: var(--font-weight-semibold);
    }
    .mono {
      font-family: var(--font-mono);
    }
    .halo {
      stroke: var(--color-surface);
      stroke-width: 5px;
      paint-order: stroke;
    }
    .line {
      fill: none;
      stroke: var(--color-text-muted);
      stroke-width: 2;
    }
    .cut {
      fill: none;
      stroke: var(--color-text);
      stroke-width: 2;
      stroke-dasharray: 6 5;
    }
    .node {
      fill: var(--room-tint);
      stroke: var(--room-color);
      stroke-width: 2;
    }
    .box {
      fill: var(--color-surface-subtle);
      stroke: var(--color-border);
      stroke-width: 2;
    }
    .box--a {
      fill: color-mix(in srgb, var(--brand-blue) 22%, var(--color-surface));
      stroke: var(--brand-blue-ink);
    }
    .box--b {
      fill: color-mix(in srgb, var(--brand-green) 24%, var(--color-surface));
      stroke: var(--brand-green-ink);
    }
    .box--unknown {
      fill: var(--color-surface);
      stroke: var(--color-text-muted);
      stroke-dasharray: 5 4;
    }
    .dot--normal {
      fill: var(--brand-blue-ink);
    }
    .dot--suspicious {
      fill: var(--brand-red-ink);
    }
    .arrow {
      fill: var(--color-text-muted);
    }
    .flip {
      fill: var(--color-warning-subtle);
      stroke: var(--brand-yellow);
      stroke-width: 2;
    }
  `,
  template: `
    <figure>
      @switch (figure().type) {
        @case ("neuron") {
          <svg [attr.viewBox]="'0 0 480 ' + neuron().height" role="img" [attr.aria-label]="alt()">
            <defs>
              <marker [id]="uid + '-arrow'" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto">
                <path d="M0 0L10 5L0 10z" class="arrow" />
              </marker>
            </defs>
            @for (input of neuron().inputs; track input.label) {
              <line class="line" [attr.x1]="66" [attr.y1]="input.cy" [attr.x2]="188" [attr.y2]="neuron().mid" [attr.marker-end]="'url(#' + uid + '-arrow)'" />
              <circle class="node" cx="44" [attr.cy]="input.cy" r="22" />
              <text class="t t--bold" x="44" [attr.y]="input.cy - 3" text-anchor="middle">{{ input.label }}</text>
              <text class="t t--small" x="44" [attr.y]="input.cy + 13" text-anchor="middle">{{ input.value }}</text>
              <text class="t t--small halo" [attr.x]="input.lx" [attr.y]="input.ly" text-anchor="middle">w = {{ input.weight }}</text>
              <text class="t t--small" [attr.x]="input.lx" [attr.y]="input.ly" text-anchor="middle">w = {{ input.weight }}</text>
            }
            <circle class="node" cx="230" [attr.cy]="neuron().mid" r="42" />
            <text class="t t--bold" x="230" [attr.y]="neuron().mid - 3" text-anchor="middle">Σ + b</text>
            <text class="t t--small" x="230" [attr.y]="neuron().mid + 15" text-anchor="middle">(b = {{ neuron().bias }})</text>
            <line class="line" x1="272" [attr.y1]="neuron().mid" x2="314" [attr.y2]="neuron().mid" [attr.marker-end]="'url(#' + uid + '-arrow)'" />
            <rect class="box box--a" x="316" [attr.y]="neuron().mid - 22" width="68" height="44" rx="8" />
            <text class="t t--bold mono" x="350" [attr.y]="neuron().mid + 5" text-anchor="middle">ReLU</text>
            <line class="line" x1="384" [attr.y1]="neuron().mid" x2="408" [attr.y2]="neuron().mid" [attr.marker-end]="'url(#' + uid + '-arrow)'" />
            <circle class="box box--unknown" cx="436" [attr.cy]="neuron().mid" r="24" />
            <text class="t t--bold" x="436" [attr.y]="neuron().mid + 6" text-anchor="middle">?</text>
          </svg>
          <figcaption>Each input is multiplied by its weight; the products and the bias are added; ReLU is applied.</figcaption>
        }
        @case ("crossover") {
          <svg viewBox="0 0 470 250" role="img" [attr.aria-label]="alt()">
            @for (cell of crossover().numbers; track cell.n) {
              <text class="t t--small t--muted" [attr.x]="cell.x + 25" y="30" text-anchor="middle">{{ cell.n }}</text>
            }
            @for (row of crossover().rows; track row.name) {
              <text class="t t--bold" x="6" [attr.y]="row.y + 31" text-anchor="start">{{ row.name }}</text>
              @for (cell of row.cells; track cell.n) {
                <rect [attr.class]="'box ' + cell.style" [attr.x]="cell.x" [attr.y]="row.y" width="50" height="46" rx="6" />
                <text class="t t--bold mono" [attr.x]="cell.x + 25" [attr.y]="row.y + 30" text-anchor="middle" style="font-size: 20px">{{ cell.text }}</text>
              }
            }
          </svg>
          <figcaption>Build the child from the two parents.</figcaption>
        }
        @case ("split") {
          <svg viewBox="0 0 470 330" role="img" [attr.aria-label]="alt()">
            <text class="t t--bold" x="235" y="20" text-anchor="middle">{{ split().question }}</text>
            <line class="line" x1="235" y1="108" x2="125" y2="170" />
            <line class="line" x1="235" y1="108" x2="345" y2="170" />
            <text class="t t--small t--bold halo" x="160" y="132" text-anchor="middle">Yes</text>
            <text class="t t--small t--bold" x="160" y="132" text-anchor="middle">Yes</text>
            <text class="t t--small t--bold halo" x="310" y="132" text-anchor="middle">No</text>
            <text class="t t--small t--bold" x="310" y="132" text-anchor="middle">No</text>
            @for (node of split().nodes; track node.name) {
              <rect class="node" [attr.x]="node.x" [attr.y]="node.y" [attr.width]="node.w" [attr.height]="node.h" rx="10" />
              @for (dot of node.dots; track $index) {
                @if (dot.suspicious) {
                  <path class="dot--suspicious" [attr.d]="'M' + dot.x + ' ' + (dot.y - 9) + 'L' + (dot.x + 9) + ' ' + dot.y + 'L' + dot.x + ' ' + (dot.y + 9) + 'L' + (dot.x - 9) + ' ' + dot.y + 'z'" />
                } @else {
                  <circle class="dot--normal" [attr.cx]="dot.x" [attr.cy]="dot.y" r="8" />
                }
              }
              <text class="t t--small" [attr.x]="node.x + node.w / 2" [attr.y]="node.y + node.h - 9" text-anchor="middle">{{ node.caption }}</text>
            }
            <circle class="dot--normal" cx="140" cy="312" r="8" />
            <text class="t t--small" x="154" y="317">normal</text>
            <path class="dot--suspicious" d="M262 303L271 312L262 321L253 312z" />
            <text class="t t--small" x="278" y="317">suspicious</text>
          </svg>
          <figcaption>A node of card swipes split by a yes or no question into two smaller nodes.</figcaption>
        }
        @case ("minimax") {
          <svg [attr.viewBox]="'0 0 ' + minimax().width + ' 252'" role="img" [attr.aria-label]="alt()">
            @for (child of minimax().children; track $index) {
              <line class="line" [attr.x1]="minimax().rootX" y1="58" [attr.x2]="child.x" y2="104" />
              @for (leaf of child.leaves; track $index) {
                <line class="line" [attr.x1]="child.x" y1="140" [attr.x2]="leaf.x" y2="182" />
              }
            }
            <rect class="node" [attr.x]="minimax().rootX - 44" y="18" width="88" height="40" rx="8" />
            <text class="t t--bold" [attr.x]="minimax().rootX" y="43" text-anchor="middle">{{ minimax().root }} ?</text>
            @for (child of minimax().children; track $index) {
              <rect class="box box--a" [attr.x]="child.x - 40" y="104" width="80" height="36" rx="8" />
              <text class="t t--bold" [attr.x]="child.x" y="127" text-anchor="middle">{{ minimax().childName }} ?</text>
              @for (leaf of child.leaves; track $index) {
                <rect class="box" [attr.x]="leaf.x - 19" y="182" width="38" height="36" rx="6" />
                <text class="t t--bold mono" [attr.x]="leaf.x" y="206" text-anchor="middle">{{ leaf.text }}</text>
              }
            }
            <text class="t t--small t--muted" x="6" y="43">{{ minimax().root }}</text>
            <text class="t t--small t--muted" x="6" y="127">{{ minimax().childName }}</text>
            <text class="t t--small t--muted" x="6" y="206">leaves</text>
          </svg>
          <figcaption>A game tree: the leaf values are known; the value of each player's node is not.</figcaption>
        }
      }
    </figure>
  `,
})
export class SentinelFigure {
  readonly figure = input.required<SentinelFigureData>();
  protected readonly uid = `figure-${nextFigure++}`;

  /** The text alternative: the question's own data in words, never an answer. */
  protected readonly alt = computed(() => {
    const figure = this.figure();
    switch (figure.type) {
      case "neuron":
        return (
          `A neuron with ${figure.inputs.map((i) => `${i.label} = ${show(i.value)}`).join(", ")}; ` +
          `weights ${figure.weights.map(show).join(" and ")}; bias b = ${show(figure.bias)}; then a ReLU. The output is unknown.`
        );
      case "crossover":
        return `Parent A ${[...figure.parentA].join(" ")} and Parent B ${[...figure.parentB].join(" ")}. The child is built from the two parents and is not shown.`;
      case "split":
        return (
          `A node of ${figure.parent.normal + figure.parent.suspicious} swipes (${figure.parent.normal} normal, ${figure.parent.suspicious} suspicious) ` +
          `split by "${figure.question}": Yes has ${figure.yes.normal} normal and ${figure.yes.suspicious} suspicious, ` +
          `No has ${figure.no.normal} normal and ${figure.no.suspicious} suspicious.`
        );
      case "minimax":
        return (
          `A ${figure.root} root above ${figure.children.length} ${figure.root === "MAX" ? "MIN" : "MAX"} nodes with leaf values ` +
          `${figure.children.map((leaves) => leaves.map(show).join(" and ")).join("; ")}. The values of the nodes are unknown.`
        );
    }
  });

  protected readonly neuron = computed(() => {
    const figure = this.figure();
    if (figure.type !== "neuron") return { height: 180, mid: 90, bias: "", inputs: [] as { label: string; value: string; weight: string; cy: number; lx: number; ly: number }[] };
    const height = Math.max(180, figure.inputs.length * 72 + 36);
    const mid = height / 2;
    const inputs = figure.inputs.map((input, index) => {
      const cy = 36 + index * 72 + (height - 36 * 2 - (figure.inputs.length - 1) * 72) / 2;
      return {
        label: input.label,
        value: show(input.value),
        weight: show(figure.weights[index] ?? 0),
        cy,
        lx: 66 + (188 - 66) * 0.5,
        ly: cy + (mid - cy) * 0.5 - 9,
      };
    });
    return { height, mid, bias: show(figure.bias), inputs };
  });

  protected readonly crossover = computed(() => {
    const figure = this.figure();
    const empty = { numbers: [] as { n: number; x: number }[], rows: [] as { name: string; y: number; cells: { n: number; x: number; text: string; style: string }[] }[] };
    if (figure.type !== "crossover") return empty;
    const x0 = 100;
    const step = 56;
    const length = figure.parentA.length;
    const at = (n: number): number => x0 + (n - 1) * step;
    // Every box is drawn the same way: nothing shows which part of a parent goes into the child.
    const cells = (bits: string) => [...bits].map((bit, i) => ({ n: i + 1, x: at(i + 1), text: bit, style: "" }));
    return {
      numbers: Array.from({ length }, (_, i) => ({ n: i + 1, x: at(i + 1) })),
      rows: [
        { name: "Parent A", y: 40, cells: cells(figure.parentA) },
        { name: "Parent B", y: 108, cells: cells(figure.parentB) },
        { name: "Child", y: 176, cells: Array.from({ length }, (_, i) => ({ n: i + 1, x: at(i + 1), text: "?", style: "box--unknown" })) },
      ],
    };
  });

  protected readonly split = computed(() => {
    const figure = this.figure();
    if (figure.type !== "split") return { question: "", nodes: [] as { name: string; x: number; y: number; w: number; h: number; caption: string; dots: { x: number; y: number; suspicious: boolean }[] }[] };
    const node = (name: string, counts: { normal: number; suspicious: number }, x: number, y: number, w: number) => {
      const total = counts.normal + counts.suspicious;
      const perRow = Math.max(1, Math.floor((w - 20) / 24));
      const dots = Array.from({ length: total }, (_, i) => ({
        x: x + 22 + (i % perRow) * 24,
        y: y + 22 + Math.floor(i / perRow) * 24,
        suspicious: i >= counts.normal,
      }));
      const rows = Math.max(1, Math.ceil(total / perRow));
      return { name, x, y, w, h: 22 + rows * 24 + 14, caption: `${counts.normal} normal · ${counts.suspicious} suspicious`, dots };
    };
    return {
      question: figure.question,
      nodes: [
        node("parent", figure.parent, 165, 32, 140),
        node("yes", figure.yes, 45, 170, 160),
        node("no", figure.no, 265, 170, 160),
      ],
    };
  });

  protected readonly minimax = computed(() => {
    const figure = this.figure();
    if (figure.type !== "minimax") return { width: 470, rootX: 235, root: "", childName: "", children: [] as { x: number; leaves: { x: number; text: string }[] }[] };
    const leafStep = 46;
    const gap = 30;
    const groups = figure.children.map((leaves) => leaves.length * leafStep - (leafStep - 38));
    const total = groups.reduce((a, b) => a + b, 0) + gap * (groups.length - 1);
    const left = 56;
    const width = Math.max(470, left + total + 16);
    let cursor = left + (width - left - total) / 2;
    const children = figure.children.map((leaves, index) => {
      const start = cursor;
      cursor += groups[index] + gap;
      const x = start + groups[index] / 2;
      return { x, leaves: leaves.map((leaf, i) => ({ x: start + 19 + i * leafStep, text: show(leaf) })) };
    });
    const first: Pt = { x: children[0]?.x ?? 0, y: 0 };
    const last: Pt = { x: children[children.length - 1]?.x ?? 0, y: 0 };
    return { width, rootX: (first.x + last.x) / 2, root: figure.root, childName: figure.root === "MAX" ? "MIN" : "MAX", children };
  });
}
