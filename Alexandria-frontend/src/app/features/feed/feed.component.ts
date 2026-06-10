import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { DocumentService } from '../../core/services/document.service';
import { RecommendationService } from '../../core/services/recommendation.service';
import { DocumentCardComponent } from '../../shared/ui/document-card.component';
import { CardSkeletonComponent } from '../../shared/ui/card-skeleton.component';
import type { DocumentSummary } from '../../core/models/document.model';

type FeedTab = 'foryou' | 'latest';
const PAGE_SIZE = 10;

@Component({
  selector: 'app-feed',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, DocumentCardComponent, CardSkeletonComponent],
  template: `
    <div class="feed">
      <div class="feed-head">
        <h1>Home</h1>
        <div class="tabs" role="tablist">
          @if (auth.isAuthenticated()) {
            <button
              type="button"
              class="tab"
              [class.active]="tab() === 'foryou'"
              (click)="selectTab('foryou')"
            >For You</button>
          }
          <button
            type="button"
            class="tab"
            [class.active]="tab() === 'latest'"
            (click)="selectTab('latest')"
          >Latest</button>
        </div>
      </div>

      @if (loading() && items().length === 0) {
        <div class="list">
          <app-card-skeleton />
          <app-card-skeleton />
          <app-card-skeleton />
        </div>
      } @else if (error()) {
        <div class="state">
          <p>Couldn't load your feed.</p>
          <button type="button" class="btn btn-secondary" (click)="reload()">Retry</button>
        </div>
      } @else if (items().length === 0) {
        <div class="state">
          @if (tab() === 'foryou') {
            <p class="state-title">No recommendations yet</p>
            <p class="text-secondary">Read a few documents and we'll tailor a feed for you.</p>
            <button type="button" class="btn btn-secondary" (click)="selectTab('latest')">
              Browse latest
            </button>
          } @else {
            <p class="state-title">Nothing here yet</p>
            <p class="text-secondary">Be the first to publish something.</p>
            @if (auth.isAuthenticated()) {
              <a routerLink="/documents/create" class="btn btn-primary">Compose</a>
            }
          }
        </div>
      } @else {
        <div class="list">
          @for (doc of items(); track doc.id) {
            <app-document-card [document]="doc" />
          }
        </div>

        @if (!last()) {
          <button
            type="button"
            class="btn btn-secondary load-more"
            [disabled]="loading()"
            (click)="loadMore()"
          >
            {{ loading() ? 'Loading…' : 'Load more' }}
          </button>
        }
      }
    </div>
  `,
  styles: `
    .feed-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      margin-bottom: 1.1rem;
    }
    h1 { margin: 0; font-size: 1.5rem; }
    .tabs {
      display: inline-flex;
      gap: 0.25rem;
      padding: 0.25rem;
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-full);
    }
    .tab {
      border: none;
      background: transparent;
      padding: 0.4rem 0.95rem;
      border-radius: var(--radius-full);
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--color-text-secondary);
      cursor: pointer;
      transition: background var(--transition), color var(--transition);
    }
    .tab.active {
      background: var(--color-accent);
      color: #fff;
    }
    .list {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .load-more {
      display: block;
      margin: 1.25rem auto 0;
    }
    .state {
      text-align: center;
      padding: 3rem 1rem;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.5rem;
    }
    .state-title { font-weight: 700; font-size: 1.05rem; margin: 0; }
    .state .btn { margin-top: 0.75rem; }
  `,
})
export class FeedComponent {
  protected readonly auth = inject(AuthService);
  private readonly documentService = inject(DocumentService);
  private readonly recommendationService = inject(RecommendationService);

  protected readonly tab = signal<FeedTab>(this.auth.isAuthenticated() ? 'foryou' : 'latest');
  protected readonly items = signal<DocumentSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly last = signal(false);

  private page = 0;

  constructor() {
    this.load();
  }

  protected selectTab(tab: FeedTab): void {
    if (tab === this.tab() && this.items().length > 0) return;
    this.tab.set(tab);
    this.reload();
  }

  protected reload(): void {
    this.page = 0;
    this.items.set([]);
    this.last.set(false);
    this.error.set(false);
    this.load();
  }

  protected loadMore(): void {
    this.page += 1;
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);
    const request$ =
      this.tab() === 'foryou'
        ? this.recommendationService.list(this.page, PAGE_SIZE)
        : this.documentService.list({ sort: 'createdAt,desc', page: this.page, size: PAGE_SIZE });

    request$.subscribe({
      next: res => {
        this.items.update(current => [...current, ...res.content]);
        this.last.set(res.last);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
