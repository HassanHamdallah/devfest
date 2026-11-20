import { ChangeDetectionStrategy, Component } from "@angular/core";
import { EventCodeText } from "./arduino-code";

interface ReferenceSection {
  title: string;
  code: string;
  /** A line under the code block. */
  note?: string;
}

/** The Arduino page of the participant guide's quick reference: syntax only, no solutions. */
const SECTIONS: readonly ReferenceSection[] = [
  {
    title: "Pins",
    code: [
      "const int sensorPin = 2;",
      "const int ledPin = 8;",
      "",
      "void setup() {",
      "  pinMode(sensorPin, INPUT);",
      "  pinMode(ledPin, OUTPUT);",
      "}",
    ].join("\n"),
  },
  {
    title: "Read input",
    code: ["int value = digitalRead(sensorPin);", "", "if (digitalRead(sensorPin) == HIGH) {", "  // ...", "}"].join("\n"),
  },
  {
    title: "Control output",
    code: ["digitalWrite(ledPin, HIGH);", "digitalWrite(ledPin, LOW);"].join("\n"),
  },
  {
    title: "Arrays",
    code: [
      "int values[4] = {2, 4, 6, 8};",
      "",
      "values[0]",
      "values[1]",
      "",
      "for (int i = 0; i < 4; i++) {",
      "  Serial.println(values[i]);",
      "}",
    ].join("\n"),
    note: "Indexing starts from 0.",
  },
  {
    title: "Timing",
    code: ["unsigned long startTime = millis();", "", "if (millis() - startTime >= 5000) {", "  // 5 seconds passed", "}"].join("\n"),
    note: "1000 ms = 1 second.",
  },
  {
    title: "Serial monitor",
    code: ["Serial.begin(9600);", "", 'Serial.print("Value: ");', "Serial.println(value);"].join("\n"),
  },
  {
    title: "Common operators",
    code: [
      "==   Equal",
      "!=   Not equal",
      "&&   AND",
      "||   OR",
      "!    NOT",
      "++   Increase by 1",
      "--   Decrease by 1",
    ]
      .map((line) => `// ${line}`)
      .join("\n"),
  },
  {
    title: "Assignment vs comparison",
    code: ["x = 5;   // assigns a value", "x == 5   // compares values"].join("\n"),
  },
];

/** A collapsible "Quick reference" (a native details element, so it is keyboard and screen-reader friendly). */
@Component({
  selector: "event-arduino-reference",
  imports: [EventCodeText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    details {
      background: var(--color-surface);
      border: var(--border-card);
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-card);
    }
    summary {
      display: flex;
      align-items: center;
      min-block-size: 48px;
      padding: 0 var(--space-5);
      font-weight: var(--font-weight-bold);
      cursor: pointer;
    }
    summary:focus-visible {
      outline: 2px solid var(--color-border-focus);
      outline-offset: -2px;
      border-radius: var(--radius-card);
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: var(--space-4);
      padding: 0 var(--space-5) var(--space-5);
    }
    section {
      display: grid;
      gap: var(--space-2);
      align-content: start;
      min-inline-size: 0;
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    p {
      margin: 0;
      font-size: var(--font-size-sm);
    }
  `,
  template: `<details data-recovery-reference="ARDUINO">
    <summary>Quick reference</summary>
    <div class="grid">
      @for (section of sections; track section.title) {
        <section>
          <h3>{{ section.title }}</h3>
          <event-code-text [code]="section.code" />
          @if (section.note) {
            <p class="event-muted">{{ section.note }}</p>
          }
        </section>
      }
    </div>
  </details>`,
})
export class EventArduinoReference {
  protected readonly sections = SECTIONS;
}
