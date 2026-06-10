import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { CategoryService } from '../../../core/services/category.service';
import { DocumentService } from '../../../core/services/document.service';
import type { Visibility } from '../../../core/models/document.model';

@Component({
  selector: 'app-document-edit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="edit">
      <header class="head">
        <h1>Edit document</h1>
        <a [routerLink]="['/documents', id()]" class="text-secondary">← Back</a>
      </header>

      @if (loadingDoc()) {
        <div class="state">Loading…</div>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" class="card form">
          <div class="field">
            <label class="field-label">Title</label>
            <input type="text" class="input" formControlName="title" />
            @if (form.get('title')?.invalid && form.get('title')?.touched) {
              <span class="error">Max 255 characters</span>
            }
          </div>

          <div class="field">
            <label class="field-label">Description</label>
            <textarea class="input" formControlName="description" rows="3"></textarea>
          </div>

          <div class="field">
            <label class="field-label">Visibility</label>
            <select class="input" formControlName="visibility">
              <option value="PUBLIC">Public</option>
              <option value="PRIVATE">Private</option>
            </select>
          </div>

          <div class="field">
            <label class="field-label">Categories</label>
            <div class="cat-grid">
              @for (cat of categories(); track cat.id) {
                <label class="cat-checkbox" [class.checked]="selectedCategories().has(cat.id)">
                  <input
                    type="checkbox"
                    [value]="cat.id"
                    [checked]="selectedCategories().has(cat.id)"
                    (change)="toggleCategory(cat.id)"
                  />
                  {{ cat.name }}
                </label>
              }
            </div>
          </div>

          @if (error()) {
            <p class="error">{{ error() }}</p>
          }

          <div class="actions">
            <button type="submit" class="btn btn-primary" [disabled]="loading()">
              {{ loading() ? 'Saving…' : 'Save changes' }}
            </button>
            <a [routerLink]="['/documents', id()]" class="btn btn-ghost">Cancel</a>
          </div>
        </form>
      }
    </div>
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    h1 { margin: 0; font-size: 1.5rem; }
    .state { text-align: center; padding: 4rem 1rem; color: var(--color-text-muted); }
    .form { padding: 1.5rem; }
    .field { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 1.25rem; }
    textarea.input { resize: vertical; }
    .error { font-size: 0.8rem; color: var(--color-danger); }
    .cat-grid { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .cat-checkbox {
      display: flex; align-items: center; gap: 0.4rem;
      font-size: 0.85rem; cursor: pointer;
      padding: 0.3rem 0.7rem;
      border: 1px solid var(--color-border);
      border-radius: var(--radius-full);
      transition: all var(--transition);
    }
    .cat-checkbox:hover { border-color: var(--color-border-strong); }
    .cat-checkbox.checked {
      background: var(--color-accent-soft);
      border-color: var(--color-accent);
      color: var(--color-accent);
    }
    .actions { display: flex; gap: 0.75rem; align-items: center; margin-top: 1.5rem; }
  `,
})
export class DocumentEditComponent {
  private readonly documentService = inject(DocumentService);
  private readonly categoryService = inject(CategoryService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  readonly id = input.required<string>();

  protected readonly loadingDoc = signal(true);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly selectedCategories = signal<Set<string>>(new Set());

  protected readonly categories = toSignal(this.categoryService.list(), { initialValue: [] });

  protected readonly form = this.fb.group({
    title: ['', [Validators.maxLength(255)]],
    description: [''],
    visibility: ['PUBLIC' as Visibility],
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.loadingDoc.set(true);
      this.documentService.get(id).subscribe({
        next: doc => {
          this.form.patchValue({
            title: doc.title,
            description: doc.description ?? '',
            visibility: doc.visibility,
          });
          this.selectedCategories.set(new Set(doc.categories.map(c => c.id)));
          this.loadingDoc.set(false);
        },
        error: () => this.loadingDoc.set(false),
      });
    });
  }

  protected toggleCategory(id: string): void {
    this.selectedCategories.update(set => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);

    const { title, description, visibility } = this.form.getRawValue();
    const categoryIds = [...this.selectedCategories()];

    this.documentService
      .update(this.id(), {
        title: title || undefined,
        description: description || undefined,
        categoryIds: categoryIds.length ? categoryIds : undefined,
        visibility: (visibility as Visibility) ?? undefined,
      })
      .subscribe({
        next: doc => this.router.navigate(['/documents', doc.id]),
        error: err => {
          this.error.set(err.error?.message ?? 'Failed to update document.');
          this.loading.set(false);
        },
      });
  }
}
