import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  signal,
} from "@angular/core";

export type UiAlertVariant = "info" | "success" | "warning" | "error";

@Component({
  selector: "ui-alert",
  templateUrl: "./ui-alert.html",
  styleUrl: "./ui-alert.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    role: "alert",
    "[class]": "'ui-alert-host ui-alert-host--' + variant()",
    "[hidden]": "!visible()",
  },
})
export class UiAlert {
  readonly variant = input<UiAlertVariant>("info");
  readonly dismissible = input(false);

  readonly dismissed = output<void>();

  protected readonly visible = signal(true);

  protected handleDismiss(): void {
    this.visible.set(false);
    this.dismissed.emit();
  }
}
