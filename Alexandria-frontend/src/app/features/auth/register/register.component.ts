import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-register',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="auth">
      <div class="card auth-card">
        <h1>Join Alexandria</h1>
        <p class="text-secondary subtitle">Create an account to publish and discuss.</p>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <div class="field">
            <label class="field-label" for="displayName">Display name</label>
            <input id="displayName" class="input" type="text" formControlName="displayName" autocomplete="name" />
            @if (form.get('displayName')?.invalid && form.get('displayName')?.touched) {
              <span class="error">2–50 characters required</span>
            }
          </div>
          <div class="field">
            <label class="field-label" for="email">Email</label>
            <input id="email" class="input" type="email" formControlName="email" autocomplete="email" />
            @if (form.get('email')?.invalid && form.get('email')?.touched) {
              <span class="error">Valid email required</span>
            }
          </div>
          <div class="field">
            <label class="field-label" for="password">Password</label>
            <input id="password" class="input" type="password" formControlName="password" autocomplete="new-password" />
            @if (form.get('password')?.invalid && form.get('password')?.touched) {
              <span class="error">At least 8 characters required</span>
            }
          </div>
          @if (error()) {
            <p class="error">{{ error() }}</p>
          }
          <button type="submit" class="btn btn-primary submit" [disabled]="form.invalid || loading()">
            {{ loading() ? 'Creating account…' : 'Create account' }}
          </button>
        </form>
        <p class="switch">Already have an account? <a routerLink="/auth/login">Sign in</a></p>
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex: 1; }
    .auth { display: flex; justify-content: center; align-items: center; width: 100%; }
    .auth-card { width: 100%; max-width: 400px; padding: 2rem; }
    h1 { margin: 0 0 0.35rem; font-size: 1.5rem; }
    .subtitle { margin: 0 0 1.5rem; font-size: 0.92rem; }
    .field { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 1rem; }
    .error { font-size: 0.8rem; color: var(--color-danger); }
    .submit { width: 100%; margin-top: 0.5rem; }
    .switch { margin-top: 1.25rem; text-align: center; font-size: 0.875rem; color: var(--color-text-secondary); }
  `,
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(50)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  protected submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);

    const { displayName, email, password } = this.form.getRawValue();
    this.auth
      .register({ displayName: displayName!, email: email!, password: password! })
      .subscribe({
        next: () => this.router.navigate(['/feed']),
        error: err => {
          this.error.set(err.error?.message ?? 'Registration failed. Please try again.');
          this.loading.set(false);
        },
      });
  }
}
