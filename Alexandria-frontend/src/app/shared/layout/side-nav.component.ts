import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-side-nav',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="side-nav">
      <a routerLink="/feed" routerLinkActive="active" class="nav-item">
        <svg viewBox="0 0 24 24" class="ic"><path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10" /></svg>
        <span class="label">Home</span>
      </a>
      <a routerLink="/explore" routerLinkActive="active" class="nav-item">
        <svg viewBox="0 0 24 24" class="ic"><circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" /></svg>
        <span class="label">Explore</span>
      </a>
      @if (auth.isAuthenticated()) {
        <a routerLink="/reading-lists" routerLinkActive="active" class="nav-item">
          <svg viewBox="0 0 24 24" class="ic"><path d="M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z" /></svg>
          <span class="label">Collections</span>
        </a>
        <a [routerLink]="['/users', auth.user()?.id]" routerLinkActive="active" class="nav-item">
          <svg viewBox="0 0 24 24" class="ic"><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 4-6 8-6s8 2 8 6" /></svg>
          <span class="label">Profile</span>
        </a>
      }
    </nav>
  `,
  styles: `
    .side-nav {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      padding: 0 0.75rem;
    }
    .nav-item {
      display: flex;
      align-items: center;
      gap: 0.85rem;
      padding: 0.7rem 0.9rem;
      border-radius: var(--radius-md);
      color: var(--color-text-secondary);
      font-size: 0.98rem;
      font-weight: 600;
      transition: background var(--transition), color var(--transition);
    }
    .nav-item:hover {
      background: var(--color-surface-hover);
      color: var(--color-text);
    }
    .nav-item.active {
      background: var(--color-accent-soft);
      color: var(--color-accent);
    }
    .ic {
      width: 22px;
      height: 22px;
      fill: none;
      stroke: currentColor;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    @media (max-width: 860px) {
      .side-nav {
        position: fixed;
        top: auto;
        bottom: 0;
        left: 0;
        right: 0;
        flex-direction: row;
        justify-content: space-around;
        width: 100%;
        padding: 0.4rem 0.5rem;
        background: var(--color-surface);
        border-top: 1px solid var(--color-border);
        z-index: 50;
      }
      .nav-item {
        flex-direction: column;
        gap: 0.2rem;
        padding: 0.35rem 0.6rem;
        font-size: 0.7rem;
      }
      .nav-item.active { background: transparent; }
    }
  `,
})
export class SideNavComponent {
  protected readonly auth = inject(AuthService);
}
