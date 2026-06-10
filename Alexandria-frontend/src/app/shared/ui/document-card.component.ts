import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AvatarComponent } from './avatar.component';
import { TimeAgoPipe } from '../pipes/time-ago.pipe';
import type { DocumentSummary } from '../../core/models/document.model';

@Component({
  selector: 'app-document-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AvatarComponent, TimeAgoPipe],
  template: `
    <article class="post">
      <header class="post-head">
        <a [routerLink]="['/users', document().author.id]" class="author">
          <app-avatar [name]="document().author.displayName" [size]="38" />
          <span class="author-meta">
            <span class="author-name">{{ document().author.displayName }}</span>
            <span class="post-sub">
              {{ document().createdAt | timeAgo }} · {{ document().type }}
            </span>
          </span>
        </a>
        @if (document().visibility === 'PRIVATE') {
          <span class="chip chip-neutral">Private</span>
        }
      </header>

      <a [routerLink]="['/documents', document().id]" class="post-body">
        <h3 class="post-title">{{ document().title }}</h3>
        @if (document().description) {
          <p class="post-desc">{{ document().description }}</p>
        }
      </a>

      @if (document().categories.length > 0) {
        <div class="post-tags">
          @for (cat of document().categories; track cat.id) {
            <span class="chip">#{{ cat.name }}</span>
          }
        </div>
      }

      <footer class="post-foot">
        <span class="kind">
          @if (document().hasBody) { ✎ Article }
          @else if (document().hasFile) { 📎 File }
        </span>
        <a [routerLink]="['/documents', document().id]" class="read-link">Read →</a>
      </footer>
    </article>
  `,
  styles: `
    .post {
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      padding: 1.1rem 1.25rem;
      transition: box-shadow var(--transition), border-color var(--transition);
    }
    .post:hover {
      box-shadow: var(--shadow-md);
      border-color: var(--color-border-strong);
    }
    .post-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      margin-bottom: 0.75rem;
    }
    .author {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      text-decoration: none;
    }
    .author-meta {
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
    }
    .author-name {
      font-weight: 600;
      font-size: 0.9rem;
      color: var(--color-text);
    }
    .author:hover .author-name {
      color: var(--color-accent);
    }
    .post-sub {
      font-size: 0.78rem;
      color: var(--color-text-muted);
      text-transform: capitalize;
    }
    .post-body {
      display: block;
      text-decoration: none;
      color: inherit;
    }
    .post-title {
      margin: 0 0 0.35rem;
      font-size: 1.15rem;
      line-height: 1.35;
      color: var(--color-text);
    }
    .post-body:hover .post-title {
      color: var(--color-accent);
    }
    .post-desc {
      margin: 0;
      color: var(--color-text-secondary);
      font-size: 0.92rem;
      line-height: 1.55;
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .post-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-top: 0.85rem;
    }
    .post-foot {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 1rem;
      padding-top: 0.85rem;
      border-top: 1px solid var(--color-border);
    }
    .kind {
      font-size: 0.8rem;
      color: var(--color-text-muted);
    }
    .read-link {
      font-size: 0.85rem;
      font-weight: 600;
      color: var(--color-accent);
    }
  `,
})
export class DocumentCardComponent {
  readonly document = input.required<DocumentSummary>();
}
