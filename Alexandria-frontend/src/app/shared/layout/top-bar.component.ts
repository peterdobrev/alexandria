import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AvatarComponent } from '../ui/avatar.component';

@Component({
  selector: 'app-top-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, AvatarComponent],
  template: `
    <header class="top-bar" [class.top-bar--auth]="isAuthPage()">
      <a routerLink="/feed" class="brand">
        <span class="brand-mark">A</span>
        <span class="brand-name">Alexandria</span>
      </a>

      <form class="search" (submit)="$event.preventDefault(); search()">
        <span class="search-icon">⌕</span>
        <input
          type="search"
          class="search-input"
          [formControl]="query"
          placeholder="Search the library…"
          aria-label="Search documents"
        />
      </form>

      <div class="actions">
        @if (auth.isAuthenticated()) {
          <a routerLink="/documents/create" class="btn btn-primary compose">
            <span class="compose-plus">＋</span>
            <span class="compose-label">Compose</span>
          </a>
          <a [routerLink]="['/users', auth.user()?.id]" class="me" aria-label="My profile">
            @if (auth.user(); as user) {
              <app-avatar [name]="user.displayName" [size]="36" />
            }
          </a>
          <button type="button" class="btn btn-ghost" (click)="auth.logout()">Logout</button>
        } @else {
          <a routerLink="/auth/login" class="btn btn-ghost">Login</a>
          <a routerLink="/auth/register" class="btn btn-primary">Sign up</a>
        }
      </div>
    </header>
  `,
  styles: `
    :host {
      display: block;
      position: sticky;
      top: 0;
      z-index: 50;
      background: rgba(255, 255, 255, 0.85);
      backdrop-filter: blur(10px);
      border-bottom: 1px solid var(--color-border);
    }
    .top-bar {
      display: flex;
      align-items: center;
      gap: 1.25rem;
      height: var(--header-height);
      padding: 0 1.5rem 0 calc(var(--sidebar-width) + 1rem);
    }
    .top-bar--auth {
      padding-left: 1.5rem;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 0.55rem;
      text-decoration: none;
      flex-shrink: 0;
    }
    .brand-mark {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border-radius: var(--radius-md);
      background: var(--color-accent);
      color: #fff;
      font-weight: 800;
      font-size: 1.1rem;
    }
    .brand-name {
      font-weight: 700;
      font-size: 1.15rem;
      color: var(--color-text);
      letter-spacing: -0.01em;
    }
    .search {
      position: relative;
      flex: 1 1 120px;
      min-width: 0;
      max-width: 460px;
    }
    .search-icon {
      position: absolute;
      left: 0.85rem;
      top: 50%;
      transform: translateY(-50%);
      color: var(--color-text-muted);
      font-size: 1.1rem;
    }
    .search-input {
      width: 100%;
      padding: 0.55rem 0.85rem 0.55rem 2.4rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-full);
      background: var(--color-surface-sunken);
      font-size: 0.9rem;
      color: var(--color-text);
      transition: border-color var(--transition), box-shadow var(--transition), background var(--transition);
    }
    .search-input::placeholder { color: var(--color-text-muted); }
    .search-input:focus {
      outline: none;
      background: var(--color-surface);
      border-color: var(--color-accent);
      box-shadow: 0 0 0 3px var(--color-accent-soft);
    }
    .actions {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      flex-shrink: 0;
      margin-left: auto;
    }
    .compose-plus { font-size: 1.05rem; line-height: 1; }
    .me { display: inline-flex; border-radius: var(--radius-full); }
    @media (max-width: 860px) {
      .top-bar { padding-left: 1rem; }
    }
    @media (max-width: 720px) {
      .brand-name { display: none; }
      .compose-label { display: none; }
    }
    @media (max-width: 480px) {
      .search { display: none; }
    }
  `,
})
export class TopBarComponent {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly isAuthPage = input(false);

  protected readonly query = new FormControl('', { nonNullable: true });

  protected search(): void {
    const term = this.query.value.trim();
    this.router.navigate(['/explore'], {
      queryParams: term ? { search: term } : {},
    });
  }
}
