import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, switchMap } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { CategoryService } from '../../../core/services/category.service';
import { DocumentService, type DocumentFilters } from '../../../core/services/document.service';
import { DocumentCardComponent } from '../../../shared/ui/document-card.component';
import { CardSkeletonComponent } from '../../../shared/ui/card-skeleton.component';
import type { CategoryResponse } from '../../../core/models/category.model';
import type { DocumentSummary } from '../../../core/models/document.model';
import type { PageResponse } from '../../../core/models/page.model';

@Component({
  selector: 'app-document-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, DocumentCardComponent, CardSkeletonComponent],
  template: `
    <div class="explore">
      <header class="explore-head">
        <h1>Explore</h1>
      </header>

      <input
        type="search"
        class="input search"
        [formControl]="searchControl"
        placeholder="Search the library…"
      />

      @if (categories().length > 0) {
        <div class="cat-row">
          <button
            type="button"
            class="cat"
            [class.active]="selectedCategory() === ''"
            (click)="selectCategory('')"
          >All</button>
          @for (cat of categories(); track cat.id) {
            <button
              type="button"
              class="cat"
              [class.active]="selectedCategory() === cat.id"
              (click)="selectCategory(cat.id)"
            >#{{ cat.name }}</button>
          }
        </div>
      }

      @if (loading()) {
        <div class="list">
          <app-card-skeleton />
          <app-card-skeleton />
          <app-card-skeleton />
        </div>
      } @else if (documents().length === 0) {
        <div class="state">
          <p class="state-title">No results</p>
          <p class="text-secondary">Try a different search or category.</p>
        </div>
      } @else {
        <div class="list">
          @for (doc of documents(); track doc.id) {
            <app-document-card [document]="doc" />
          }
        </div>

        <div class="pagination">
          <button
            type="button"
            class="btn btn-ghost"
            [disabled]="pageInfo().page === 0"
            (click)="goToPage(pageInfo().page - 1)"
          >← Prev</button>
          <span class="text-muted">Page {{ pageInfo().page + 1 }} of {{ pageInfo().totalPages || 1 }}</span>
          <button
            type="button"
            class="btn btn-ghost"
            [disabled]="pageInfo().last"
            (click)="goToPage(pageInfo().page + 1)"
          >Next →</button>
        </div>
      }
    </div>
  `,
  styles: `
    .explore-head { margin-bottom: 1rem; }
    h1 { margin: 0; font-size: 1.5rem; }
    .search { margin-bottom: 1rem; }
    .cat-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-bottom: 1.25rem;
    }
    .cat {
      border: 1px solid var(--color-border);
      background: var(--color-surface);
      color: var(--color-text-secondary);
      padding: 0.35rem 0.85rem;
      border-radius: var(--radius-full);
      font-size: 0.82rem;
      font-weight: 600;
      cursor: pointer;
      transition: all var(--transition);
    }
    .cat:hover { border-color: var(--color-border-strong); color: var(--color-text); }
    .cat.active {
      background: var(--color-accent);
      border-color: var(--color-accent);
      color: #fff;
    }
    .list { display: flex; flex-direction: column; gap: 1rem; }
    .state { text-align: center; padding: 3rem 1rem; }
    .state-title { font-weight: 700; font-size: 1.05rem; margin: 0 0 0.25rem; }
    .pagination { display: flex; justify-content: center; align-items: center; gap: 1rem; margin-top: 1.5rem; }
  `,
})
export class DocumentListComponent {
  private readonly documentService = inject(DocumentService);
  private readonly categoryService = inject(CategoryService);
  private readonly route = inject(ActivatedRoute);

  protected readonly documents = signal<DocumentSummary[]>([]);
  protected readonly categories = signal<CategoryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly pageInfo = signal({ page: 0, totalPages: 0, last: true });
  protected readonly selectedCategory = signal('');

  protected readonly searchControl = new FormControl('', { nonNullable: true });

  private currentPage = 0;
  private readonly loadTrigger$ = new Subject<void>();

  constructor() {
    this.categoryService
      .list()
      .pipe(takeUntilDestroyed())
      .subscribe(cats => this.categories.set(cats));

    this.loadTrigger$
      .pipe(
        switchMap(() => {
          this.loading.set(true);
          const filters: DocumentFilters = {
            search: this.searchControl.value || undefined,
            categoryId: this.selectedCategory() || undefined,
            sort: 'createdAt,desc',
            page: this.currentPage,
            size: 12,
          };
          return this.documentService.list(filters);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (res: PageResponse<DocumentSummary>) => {
          this.documents.set(res.content);
          this.pageInfo.set({ page: res.page, totalPages: res.totalPages, last: res.last });
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });

    // Seed/refresh search from the ?search= query param (top-bar search).
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe(params => {
      const term = params.get('search') ?? '';
      if (term !== this.searchControl.value) {
        this.searchControl.setValue(term, { emitEvent: false });
      }
      this.currentPage = 0;
      this.loadTrigger$.next();
    });

    this.searchControl.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe(() => {
        this.currentPage = 0;
        this.loadTrigger$.next();
      });
  }

  protected selectCategory(id: string): void {
    if (id === this.selectedCategory()) return;
    this.selectedCategory.set(id);
    this.currentPage = 0;
    this.loadTrigger$.next();
  }

  protected goToPage(page: number): void {
    this.currentPage = page;
    this.loadTrigger$.next();
  }
}
