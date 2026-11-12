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
import { AppCredit } from "../core/shell/app-credit";
import { AppLogo } from "../shared/ui/app-logo/app-logo";
import { BrandBar } from "../shared/ui/brand-bar/brand-bar";
import { UiInput } from "../shared/ui/ui-input/ui-input";
import { UiButton } from "../shared/ui/ui-button/ui-button";

/** Sign-in for every role: one centred card on the cream page, with the GDG logo (its own four-colour ring). */
@Component({
  imports: [AppCredit, AppLogo, BrandBar, ReactiveFormsModule, UiInput, UiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-brand-bar class="login__bar" />
    <div class="login-page">
    <main class="login">
      <section class="login__card" aria-labelledby="login-title">
        <app-logo [size]="132" />
        <h1 id="login-title">DevFest · Investigation</h1>
        <p class="login__lead">
          Sign in with the account your organizer gave you. Organizers, room
          managers and teams each land on their own page.
        </p>
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
            size="lg"
            [loading]="busy()"
            [disabled]="form.invalid"
            [fullWidth]="true"
            >Sign in</ui-button
          >
        </form>
      </section>
      <p class="login__footer">GDG On Campus · Birzeit University</p>
    </main>
    <app-credit />
    </div>`,
  styles: `
    .login__bar {
      position: fixed;
      inset: 0 0 auto;
    }
    .login-page {
      display: flex;
      flex-direction: column;
      min-block-size: 100dvh;
    }
    .login {
      flex: 1;
      display: grid;
      place-content: center;
      justify-items: center;
      gap: var(--space-5);
      padding: var(--space-10) var(--space-4);
    }
    .login__card {
      display: grid;
      justify-items: center;
      gap: var(--space-3);
      inline-size: min(26rem, calc(100vw - 2 * var(--space-4)));
      padding: var(--space-8) var(--space-6) var(--space-6);
      text-align: center;
      background: var(--color-surface);
      border: var(--border-outline);
      border-radius: 28px;
    }
    h1 {
      margin: var(--space-2) 0 0;
      font-size: 1.75rem;
    }
    .login__lead {
      margin: 0 0 var(--space-2);
      font-size: var(--font-size-sm);
      color: var(--color-text-muted);
    }
    form {
      inline-size: 100%;
      text-align: start;
    }
    .login__banner {
      margin: 0;
      padding: var(--space-3) var(--space-4);
      font-size: var(--font-size-sm);
      color: var(--color-error);
      background: var(--color-error-subtle);
      border: 1px solid color-mix(in srgb, var(--brand-red) 40%, transparent);
      border-inline-start: 4px solid var(--brand-red);
      border-radius: var(--radius-md);
    }
    .login__footer {
      margin: 0;
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-muted);
    }
    @media (max-width: 480px) {
      .login__card {
        padding: var(--space-6) var(--space-4) var(--space-5);
      }
    }
  `,
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
