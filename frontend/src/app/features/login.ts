import { Component, inject, signal } from "@angular/core";
import {
  ReactiveFormsModule,
  FormControl,
  FormGroup,
  Validators,
} from "@angular/forms";
import { Router } from "@angular/router";
import { Api, message } from "../core/api";
import { UiInput } from "../shared/ui/ui-input/ui-input";
import { UiButton } from "../shared/ui/ui-button/ui-button";
import { UiCard } from "../shared/ui/ui-card/ui-card";
@Component({
  imports: [ReactiveFormsModule, UiInput, UiButton, UiCard],
  template: `<div class="login">
    <section>
      <span class="eyebrow">GDG · DEVFEST</span>
      <h1>Your ideas.<br />Your code.<br />Your moment.</h1>
      <p>
        A focused programming contest. Read the challenge, build your solution,
        and make it count.
      </p>
      <div class="language-pills">C++ <span>Java</span> Python</div>
    </section>
    <ui-card
      ><h2>Welcome to Code Arena</h2>
      <p>Sign in with the account provided by your organizer.</p>
      <form [formGroup]="form" (ngSubmit)="login()">
        <ui-input label="Email" type="email" formControlName="email" /><ui-input
          label="Password"
          type="password"
          formControlName="password"
        />
        @if (error()) {
          <p role="alert">{{ error() }}</p>
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
  api = inject(Api);
  router = inject(Router);
  error = signal("");
  busy = signal(false);
  form = new FormGroup({
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
    try {
      const r = this.form.getRawValue();
      await this.api.login(r.email, r.password);
      await this.router.navigateByUrl(
        ["MEMBER", "MANAGER"].includes(this.api.user()?.role || "")
          ? "/investigation"
          : "/contest",
      );
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}
