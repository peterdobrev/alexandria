import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { CategoryService } from '../../../core/services/category.service';
import { DocumentService } from '../../../core/services/document.service';
import type { Visibility } from '../../../core/models/document.model';

type CreateMode = 'article' | 'file';

@Component({
  selector: 'app-document-create',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="compose">
      <header class="head">
        <h1>Compose</h1>
        <a routerLink="/feed" class="text-secondary">← Back</a>
      </header>

      <div class="mode-tabs">
        <button type="button" class="mode" [class.active]="mode() === 'article'" (click)="mode.set('article')">
          ✎ Write article
        </button>
        <button type="button" class="mode" [class.active]="mode() === 'file'" (click)="mode.set('file')">
          📎 Upload file
        </button>
      </div>

      <form [formGroup]="form" (ngSubmit)="submit()" class="card form">
        <div class="field">
          <label class="field-label">Title *</label>
          <input type="text" class="input" formControlName="title" />
          @if (form.get('title')?.invalid && form.get('title')?.touched) {
            <span class="error">Title is required (max 255 characters)</span>
          }
        </div>

        <div class="field">
          <label class="field-label">Type *</label>
          <input type="text" class="input" formControlName="type" placeholder="e.g. Book, Article, Guide, Paper…" />
          @if (form.get('type')?.invalid && form.get('type')?.touched) {
            <span class="error">Type is required (max 50 characters)</span>
          }
        </div>

        <div class="field">
          <label class="field-label">Description</label>
          <textarea class="input" formControlName="description" rows="3" placeholder="Optional summary…"></textarea>
        </div>

        @if (mode() === 'article') {
          <div class="field">
            <label class="field-label">Body *</label>
            <textarea class="input" formControlName="body" rows="10" placeholder="Write your article…"></textarea>
            @if (form.get('body')?.invalid && form.get('body')?.touched) {
              <span class="error">Body is required for articles</span>
            }
          </div>
        } @else {
          <div class="field">
            <label class="field-label">File *</label>
            <input type="file" class="input" (change)="onFileSelected($event)" />
            @if (fileRequired()) {
              <span class="error">Please select a file</span>
            }
          </div>
        }

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
          <button type="submit" class="btn btn-primary" [disabled]="form.invalid || loading()">
            {{ loading() ? 'Publishing…' : 'Publish' }}
          </button>
          <a routerLink="/feed" class="btn btn-ghost">Cancel</a>
        </div>
      </form>
    </div>
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.25rem; }
    h1 { margin: 0; font-size: 1.5rem; }
    .mode-tabs {
      display: inline-flex;
      gap: 0.25rem;
      padding: 0.25rem;
      background: var(--color-surface);
      border: 1px solid var(--color-border);
      border-radius: var(--radius-full);
      margin-bottom: 1.25rem;
    }
    .mode {
      border: none;
      background: transparent;
      padding: 0.45rem 1rem;
      border-radius: var(--radius-full);
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--color-text-secondary);
      cursor: pointer;
      transition: background var(--transition), color var(--transition);
    }
    .mode.active { background: var(--color-accent); color: #fff; }
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
export class DocumentCreateComponent {
  private readonly documentService = inject(DocumentService);
  private readonly categoryService = inject(CategoryService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);

  protected readonly mode = signal<CreateMode>('article');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fileRequired = signal(false);
  protected readonly selectedCategories = signal<Set<string>>(new Set());

  protected readonly categories = toSignal(this.categoryService.list(), {
    initialValue: [],
  });

  private selectedFile: File | null = null;

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
    type: ['', [Validators.required, Validators.maxLength(50)]],
    description: [''],
    body: [''],
    visibility: ['PUBLIC' as Visibility],
  });

  constructor() {
    effect(() => {
      const bodyCtrl = this.form.get('body')!;
      if (this.mode() === 'article') {
        bodyCtrl.setValidators(Validators.required);
      } else {
        bodyCtrl.clearValidators();
      }
      bodyCtrl.updateValueAndValidity();
    });
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
    this.fileRequired.set(false);
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
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.mode() === 'file' && !this.selectedFile) {
      this.fileRequired.set(true);
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    const { title, type, description, body, visibility } = this.form.getRawValue();
    const categoryIds = [...this.selectedCategories()];

    if (this.mode() === 'article') {
      this.documentService
        .createArticle({
          title: title!,
          type: type!,
          description: description || undefined,
          body: body!,
          categoryIds: categoryIds.length ? categoryIds : undefined,
          visibility: (visibility as Visibility) ?? 'PUBLIC',
        })
        .subscribe({
          next: doc => this.router.navigate(['/documents', doc.id]),
          error: err => {
            this.error.set(err.error?.message ?? 'Failed to create document.');
            this.loading.set(false);
          },
        });
    } else {
      this.documentService
        .create(
          {
            title: title!,
            type: type!,
            description: description || undefined,
            categoryIds: categoryIds.length ? categoryIds : undefined,
            visibility: (visibility as Visibility) ?? 'PUBLIC',
          },
          this.selectedFile!,
        )
        .subscribe({
          next: doc => this.router.navigate(['/documents', doc.id]),
          error: err => {
            this.error.set(err.error?.message ?? 'Failed to upload document.');
            this.loading.set(false);
          },
        });
    }
  }
}
