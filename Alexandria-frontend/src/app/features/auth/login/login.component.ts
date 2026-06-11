import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="auth">
      <div class="card auth-card">
        <h1>Welcome back</h1>
        <p class="text-secondary subtitle">Sign in to continue to Alexandria.</p>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <div class="field">
            <label class="field-label" for="email">Email</label>
            <input id="email" class="input" type="email" formControlName="email" autocomplete="email" />
            @if (form.get('email')?.invalid && form.get('email')?.touched) {
              <span class="error">Valid email required</span>
            }
          </div>
          <div class="field">
            <label class="field-label" for="password">Password</label>
            <input id="password" class="input" type="password" formControlName="password" autocomplete="current-password" />
          </div>
          @if (error()) {
            <p class="error">{{ error() }}</p>
          }
          <button type="submit" class="btn btn-primary submit" [disabled]="form.invalid || loading()">
            {{ loading() ? 'Signing in…' : 'Sign In' }}
          </button>
        </form>
        <p class="switch">Don't have an account? <a routerLink="/auth/register">Sign up</a></p>
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
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);

    const { email, password } = this.form.getRawValue();
    this.auth.login({ email: email!, password: password! }).subscribe({
      next: () => this.router.navigate(['/feed']),
      error: err => {
        this.error.set(err.error?.message ?? 'Invalid credentials. Please try again.');
        this.loading.set(false);
      },
    });
  }
}
