import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from "@angular/core";
import {
  ReactiveFormsModule,
  FormControl,
  FormGroup,
  Validators,
} from "@angular/forms";
import { Router } from "@angular/router";
import { Api, message } from "../core/api";
import { RoleHome } from "../core/role-home";
import { UiInput } from "../shared/ui/ui-input/ui-input";
import { UiButton } from "../shared/ui/ui-button/ui-button";
import { UiCard } from "../shared/ui/ui-card/ui-card";
@Component({
  imports: [ReactiveFormsModule, UiInput, UiButton, UiCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="login">
    <section>
      <span class="eyebrow">GDG · DEVFEST</span>
      <h1>Welcome to DevFest.</h1>
      <p>
        One sign-in for every DevFest activity. Organizers, room managers and
        teams each land on their own page after signing in.
      </p>
    </section>
    <ui-card
      ><h2>Sign in to continue</h2>
      <p>Use the account provided by your organizer.</p>
      <form [formGroup]="form" (ngSubmit)="login()">
        <ui-input
          label="Email"
          type="email"
          autocomplete="username"
          formControlName="email"
        /><ui-input
          label="Password"
          type="password"
          autocomplete="current-password"
          formControlName="password"
        />
        @if (banner(); as text) {
          <p class="login__banner" role="alert">{{ text }}</p>
        }
        <ui-button
          type="submit"
          [loading]="busy()"
          [disabled]="form.invalid"
          [fullWidth]="true"
          >Sign in</ui-button
        >
      </form></ui-card
    >
  </div>`,
})
export class Login {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly roleHome = inject(RoleHome);
  protected readonly error = signal("");
  protected readonly busy = signal(false);
  /** One banner: the sign-in error, else why the previous session ended. */
  protected readonly banner = computed(
    () => this.error() || this.api.sessionNotice(),
  );
  protected readonly form = new FormGroup({
    email: new FormControl("", {
      nonNullable: true,
      validators: [Validators.required, Validators.email],
    }),
    password: new FormControl("", {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });
  async login() {
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set("");
    this.api.sessionNotice.set(null);
    try {
      const r = this.form.getRawValue();
      await this.api.login(r.email, r.password);
      await this.router.navigateByUrl(await this.roleHome.url());
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}
