import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReadingListService } from '../../../core/services/reading-list.service';
import { TimeAgoPipe } from '../../../shared/pipes/time-ago.pipe';
import type { ReadingListSummaryResponse } from '../../../core/models/reading-list.model';

@Component({
  selector: 'app-reading-list-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ReactiveFormsModule, TimeAgoPipe],
  template: `
    <div class="collections">
      <header class="head">
        <h1>Collections</h1>
        <p class="text-secondary">Curated shelves of documents you want to keep.</p>
      </header>

      <form class="create-form" (submit)="$event.preventDefault(); createList()">
        <input
          type="text"
          class="input"
          [formControl]="nameControl"
          placeholder="Name a new collection…"
        />
        <button type="submit" class="btn btn-primary" [disabled]="nameControl.invalid || creating()">
          {{ creating() ? 'Creating…' : 'Create' }}
        </button>
      </form>

      @if (loading()) {
        <div class="state">Loading…</div>
      } @else if (lists().length === 0) {
        <div class="state">
          <p class="state-title">No collections yet</p>
          <p class="text-secondary">Create one above to start saving documents.</p>
        </div>
      } @else {
        <div class="grid">
          @for (list of lists(); track list.id) {
            <a [routerLink]="['/reading-lists', list.id]" class="collection-card">
              <span class="folder">🗂️</span>
              <h3>{{ list.name }}</h3>
              <span class="text-muted date">Created {{ list.createdAt | timeAgo }}</span>
            </a>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .head { margin-bottom: 1.25rem; }
    h1 { margin: 0 0 0.25rem; font-size: 1.5rem; }
    .head p { margin: 0; font-size: 0.92rem; }
    .create-form { display: flex; gap: 0.6rem; margin-bottom: 1.75rem; }
    .state { text-align: center; padding: 3rem 1rem; color: var(--color-text-muted); }
    .state-title { font-weight: 700; font-size: 1.05rem; margin: 0 0 0.25rem; color: var(--color-text); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 1rem;
    }
    .collection-card {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      padding: 1.25rem;
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm);
      color: inherit;
      transition: box-shadow var(--transition), border-color var(--transition);
    }
    .collection-card:hover {
      box-shadow: var(--shadow-md);
      border-color: var(--color-border-strong);
    }
    .folder { font-size: 1.5rem; }
    h3 { margin: 0; font-size: 1.05rem; }
    .date { font-size: 0.8rem; }
  `,
})
export class ReadingListListComponent {
  private readonly readingListService = inject(ReadingListService);

  protected readonly lists = signal<ReadingListSummaryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly creating = signal(false);

  protected readonly nameControl = new FormControl('', [
    Validators.required,
    Validators.minLength(1),
  ]);

  constructor() {
    this.readingListService
      .getReadingLists()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: res => {
          this.lists.set(res.content);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  protected createList(): void {
    const name = this.nameControl.value?.trim();
    if (!name) return;
    this.creating.set(true);
    this.readingListService.createReadingList({ name }).subscribe({
      next: res => {
        this.lists.update(l => [{ id: res.id, name: res.name, createdAt: res.createdAt }, ...l]);
        this.nameControl.reset();
        this.creating.set(false);
      },
      error: () => this.creating.set(false),
    });
  }
}
