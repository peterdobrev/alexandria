import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-card-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="post">
      <div class="head">
        <span class="skeleton avatar"></span>
        <span class="lines">
          <span class="skeleton line-sm"></span>
          <span class="skeleton line-xs"></span>
        </span>
      </div>
      <span class="skeleton line-title"></span>
      <span class="skeleton line-full"></span>
      <span class="skeleton line-full"></span>
      <span class="skeleton line-half"></span>
    </div>
  `,
  styles: `
    .post {
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      padding: 1.1rem 1.25rem;
      display: flex;
      flex-direction: column;
      gap: 0.6rem;
    }
    .head {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin-bottom: 0.4rem;
    }
    .lines {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .avatar {
      width: 38px;
      height: 38px;
      border-radius: var(--radius-full);
    }
    .line-xs { width: 70px; height: 9px; }
    .line-sm { width: 120px; height: 11px; }
    .line-half { width: 45%; height: 12px; }
    .line-full { width: 100%; height: 12px; }
    .line-title { width: 75%; height: 18px; margin: 0.2rem 0 0.4rem; }
  `,
})
export class CardSkeletonComponent {}
