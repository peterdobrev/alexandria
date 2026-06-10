import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ReadingListService } from '../../../core/services/reading-list.service';
import { AvatarComponent } from '../../../shared/ui/avatar.component';
import { TimeAgoPipe } from '../../../shared/pipes/time-ago.pipe';
import type { ReadingListResponse } from '../../../core/models/reading-list.model';

@Component({
  selector: 'app-reading-list-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ReactiveFormsModule, AvatarComponent, TimeAgoPipe],
  template: `
    @if (loading()) {
      <div class="state">Loading…</div>
    } @else {
      @if (list(); as list) {
      <header class="card head">
        @if (editing()) {
          <div class="edit-name">
            <input type="text" class="input" [formControl]="nameControl" />
            <button type="button" class="btn btn-primary" (click)="saveName()" [disabled]="nameControl.invalid">
              Save
            </button>
            <button type="button" class="btn btn-ghost" (click)="editing.set(false)">Cancel</button>
          </div>
        } @else {
          <div class="title-row">
            <span class="folder">🗂️</span>
            <div>
              <h1>{{ list.name }}</h1>
              <span class="text-muted">{{ list.items.length }} {{ list.items.length === 1 ? 'item' : 'items' }} · created {{ list.createdAt | timeAgo }}</span>
            </div>
          </div>
          <div class="head-actions">
            <button type="button" class="btn btn-secondary" (click)="startEditing()">Rename</button>
            <button type="button" class="btn btn-danger" (click)="deleteList()">Delete</button>
          </div>
        }
      </header>

      <div class="add-row">
        <input
          type="text"
          class="input"
          [formControl]="documentIdControl"
          placeholder="Paste a document ID to add…"
        />
        <button type="button" class="btn btn-primary" (click)="addItem()" [disabled]="documentIdControl.invalid || adding()">
          {{ adding() ? 'Adding…' : 'Add' }}
        </button>
      </div>
      @if (addError()) {
        <p class="add-error">{{ addError() }}</p>
      }

      @if (list.items.length === 0) {
        <div class="state">
          <p class="text-secondary">No documents in this collection yet.</p>
        </div>
      } @else {
        <div class="items">
          @for (item of list.items; track item.id) {
            <div class="item">
              <app-avatar [name]="item.document.author.displayName" [size]="38" />
              <div class="item-meta">
                <a [routerLink]="['/documents', item.document.id]" class="item-title">
                  {{ item.document.title }}
                </a>
                <span class="text-muted item-sub">
                  {{ item.document.author.displayName }} · added {{ item.addedAt | timeAgo }}
                </span>
              </div>
              <button type="button" class="btn btn-ghost" (click)="removeItem(item.document.id)">
                Remove
              </button>
            </div>
          }
        </div>
      }
      } @else {
        <div class="state">Collection not found.</div>
      }
    }
  `,
  styles: `
    .state { text-align: center; padding: 4rem 1rem; color: var(--color-text-muted); }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      flex-wrap: wrap;
      padding: 1.25rem 1.5rem;
      margin-bottom: 1.25rem;
    }
    .title-row { display: flex; align-items: center; gap: 0.85rem; }
    .folder { font-size: 1.75rem; }
    h1 { margin: 0; font-size: 1.4rem; }
    .head-actions { display: flex; gap: 0.6rem; }
    .edit-name { display: flex; gap: 0.6rem; align-items: center; width: 100%; }
    .edit-name .input { flex: 1; }
    .add-row { display: flex; gap: 0.6rem; margin-bottom: 0.5rem; }
    .add-row .input { flex: 1; }
    .add-error { color: var(--color-danger); font-size: 0.875rem; margin: 0.25rem 0 1rem; }
    .items { display: flex; flex-direction: column; gap: 0.6rem; margin-top: 1rem; }
    .item {
      display: flex;
      align-items: center;
      gap: 0.85rem;
      padding: 0.85rem 1.1rem;
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-md);
    }
    .item-meta { flex: 1; display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
    .item-title { font-weight: 600; color: var(--color-text); }
    .item-title:hover { color: var(--color-accent); }
    .item-sub { font-size: 0.8rem; }
  `,
})
export class ReadingListDetailComponent {
  private readonly readingListService = inject(ReadingListService);
  private readonly router = inject(Router);

  readonly id = input.required<string>();

  protected readonly list = signal<ReadingListResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly editing = signal(false);
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);

  protected readonly nameControl = new FormControl('', Validators.required);
  protected readonly documentIdControl = new FormControl('', Validators.required);

  constructor() {
    effect(() => {
      const id = this.id();
      this.loading.set(true);
      this.readingListService.getReadingList(id).subscribe({
        next: res => {
          this.list.set(res);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
    });
  }

  protected startEditing(): void {
    this.nameControl.setValue(this.list()?.name ?? '');
    this.editing.set(true);
  }

  protected saveName(): void {
    const name = this.nameControl.value?.trim();
    if (!name) return;
    this.readingListService.updateReadingList(this.id(), { name }).subscribe(res => {
      this.list.set(res);
      this.editing.set(false);
    });
  }

  protected deleteList(): void {
    if (!confirm('Delete this collection?')) return;
    this.readingListService.deleteReadingList(this.id()).subscribe(() => {
      this.router.navigate(['/reading-lists']);
    });
  }

  protected addItem(): void {
    const documentId = this.documentIdControl.value?.trim();
    if (!documentId) return;
    this.adding.set(true);
    this.addError.set(null);
    this.readingListService.addItem(this.id(), { documentId }).subscribe({
      next: item => {
        this.list.update(l => (l ? { ...l, items: [...l.items, item] } : l));
        this.documentIdControl.reset();
        this.adding.set(false);
      },
      error: err => {
        this.addError.set(err.error?.message ?? 'Failed to add document.');
        this.adding.set(false);
      },
    });
  }

  protected removeItem(documentId: string): void {
    this.readingListService.removeItem(this.id(), documentId).subscribe(() => {
      this.list.update(l =>
        l ? { ...l, items: l.items.filter(i => i.document.id !== documentId) } : l,
      );
    });
  }
}
