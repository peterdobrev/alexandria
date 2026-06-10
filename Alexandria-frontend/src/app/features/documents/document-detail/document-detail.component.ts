import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { CommentService } from '../../../core/services/comment.service';
import { DocumentService } from '../../../core/services/document.service';
import { InteractionService } from '../../../core/services/interaction.service';
import { AvatarComponent } from '../../../shared/ui/avatar.component';
import { TimeAgoPipe } from '../../../shared/pipes/time-ago.pipe';
import type { CommentResponse } from '../../../core/models/comment.model';
import type { DocumentDetail } from '../../../core/models/document.model';

@Component({
  selector: 'app-document-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ReactiveFormsModule, AvatarComponent, TimeAgoPipe],
  template: `
    @if (loading()) {
      <div class="state">Loading…</div>
    } @else {
      @if (document(); as doc) {
      <article class="card post">
        <header class="post-head">
          <a [routerLink]="['/users', doc.author.id]" class="author">
            <app-avatar [name]="doc.author.displayName" [size]="44" />
            <span class="author-meta">
              <span class="author-name">{{ doc.author.displayName }}</span>
              <span class="post-sub">{{ doc.createdAt | timeAgo }} · {{ doc.type }}</span>
            </span>
          </a>
          @if (doc.visibility === 'PRIVATE') {
            <span class="chip chip-neutral">Private</span>
          }
        </header>

        <h1 class="title">{{ doc.title }}</h1>

        @if (doc.categories.length > 0) {
          <div class="tags">
            @for (cat of doc.categories; track cat.id) {
              <span class="chip">#{{ cat.name }}</span>
            }
          </div>
        }

        @if (doc.description) {
          <p class="description">{{ doc.description }}</p>
        }

        @if (isOwner()) {
          <div class="owner-actions">
            <a [routerLink]="['/documents', doc.id, 'edit']" class="btn btn-secondary">Edit</a>
            <button type="button" class="btn btn-danger" (click)="deleteDocument()">Delete</button>
          </div>
        }

        @if (doc.hasFile) {
          <a [href]="fileUrl()" target="_blank" class="file-link">
            <span class="file-ic">📎</span>
            <span>
              View / download file
              @if (doc.sizeBytes) {
                <span class="text-muted">({{ formatSize(doc.sizeBytes) }})</span>
              }
            </span>
          </a>
        }

        @if (doc.hasBody && doc.body) {
          <div class="body"><pre>{{ doc.body }}</pre></div>
        }
      </article>

      <section class="card comments">
        <h2>Discussion</h2>

        @if (auth.isAuthenticated()) {
          <form class="comment-form" (submit)="$event.preventDefault(); addComment()">
            @if (auth.user(); as user) {
              <app-avatar [name]="user.displayName" [size]="36" />
            }
            <div class="comment-input">
              <textarea
                class="input"
                [formControl]="commentControl"
                placeholder="Add to the discussion…"
                rows="2"
              ></textarea>
              <button
                type="submit"
                class="btn btn-primary"
                [disabled]="commentControl.invalid || commentSubmitting()"
              >
                {{ commentSubmitting() ? 'Posting…' : 'Post' }}
              </button>
            </div>
          </form>
        } @else {
          <p class="text-secondary signin-hint">
            <a routerLink="/auth/login">Sign in</a> to join the discussion.
          </p>
        }

        @if (commentsLoading()) {
          <p class="text-muted">Loading comments…</p>
        } @else if (comments().length === 0) {
          <p class="text-muted">No comments yet. Start the conversation.</p>
        } @else {
          <div class="comment-list">
            @for (comment of comments(); track comment.id) {
              <div class="comment">
                <a [routerLink]="['/users', comment.author.id]">
                  <app-avatar [name]="comment.author.displayName" [size]="36" />
                </a>
                <div class="comment-body">
                  <div class="comment-top">
                    <a [routerLink]="['/users', comment.author.id]" class="comment-author">
                      {{ comment.author.displayName }}
                    </a>
                    <span class="text-muted comment-time">{{ comment.createdAt | timeAgo }}</span>
                    @if (canDeleteComment(comment)) {
                      <button
                        type="button"
                        class="comment-delete"
                        (click)="deleteComment(comment.id)"
                        aria-label="Delete comment"
                      >×</button>
                    }
                  </div>
                  <p class="comment-text">{{ comment.body }}</p>
                </div>
              </div>
            }
          </div>

          <div class="pagination">
            <button
              type="button"
              class="btn btn-ghost"
              [disabled]="commentPage() === 0"
              (click)="loadComments(commentPage() - 1)"
            >← Prev</button>
            <span class="text-muted">Page {{ commentPage() + 1 }}</span>
            <button
              type="button"
              class="btn btn-ghost"
              [disabled]="commentsLast()"
              (click)="loadComments(commentPage() + 1)"
            >Next →</button>
          </div>
        }
      </section>
      } @else {
        <div class="state">Document not found.</div>
      }
    }
  `,
  styles: `
    .state { text-align: center; padding: 4rem 1rem; color: var(--color-text-muted); }
    .post { padding: 1.5rem; }
    .post-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.1rem; }
    .author { display: flex; align-items: center; gap: 0.7rem; }
    .author-meta { display: flex; flex-direction: column; gap: 0.15rem; }
    .author-name { font-weight: 600; color: var(--color-text); }
    .author:hover .author-name { color: var(--color-accent); }
    .post-sub { font-size: 0.8rem; color: var(--color-text-muted); text-transform: capitalize; }
    .title { font-size: 1.85rem; line-height: 1.25; margin: 0 0 0.75rem; }
    .tags { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-bottom: 1rem; }
    .description { color: var(--color-text-secondary); line-height: 1.65; margin: 0 0 1.25rem; }
    .owner-actions { display: flex; gap: 0.6rem; margin-bottom: 1.25rem; }
    .file-link {
      display: inline-flex; align-items: center; gap: 0.6rem;
      padding: 0.75rem 1.1rem; margin-bottom: 1.25rem;
      background: var(--color-surface-sunken);
      border: 1px solid var(--color-border); border-radius: var(--radius-md);
      color: var(--color-text); font-size: 0.92rem; font-weight: 500;
    }
    .file-link:hover { border-color: var(--color-accent); }
    .file-ic { font-size: 1.1rem; }
    .body {
      margin-top: 0.5rem; padding: 1.25rem;
      background: var(--color-surface-sunken);
      border: 1px solid var(--color-border); border-radius: var(--radius-md);
    }
    .body pre {
      white-space: pre-wrap; word-wrap: break-word;
      font-family: inherit; font-size: 0.97rem; line-height: 1.75;
      color: var(--color-text); margin: 0;
    }
    .comments { margin-top: 1.25rem; padding: 1.5rem; }
    .comments h2 { font-size: 1.15rem; margin: 0 0 1.1rem; }
    .comment-form { display: flex; gap: 0.7rem; margin-bottom: 1.5rem; }
    .comment-input { flex: 1; display: flex; flex-direction: column; gap: 0.5rem; align-items: flex-end; }
    .comment-input textarea { resize: vertical; }
    .signin-hint { margin: 0 0 1.25rem; }
    .comment-list { display: flex; flex-direction: column; gap: 1.1rem; }
    .comment { display: flex; gap: 0.7rem; }
    .comment-body { flex: 1; }
    .comment-top { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 0.2rem; }
    .comment-author { font-weight: 600; font-size: 0.9rem; color: var(--color-text); }
    .comment-author:hover { color: var(--color-accent); }
    .comment-time { font-size: 0.8rem; }
    .comment-delete {
      margin-left: auto; background: none; border: none; cursor: pointer;
      color: var(--color-text-muted); font-size: 1.1rem; line-height: 1; padding: 0 0.25rem;
    }
    .comment-delete:hover { color: var(--color-danger); }
    .comment-text { margin: 0; color: var(--color-text-secondary); line-height: 1.55; white-space: pre-wrap; }
    .pagination { display: flex; justify-content: center; align-items: center; gap: 1rem; margin-top: 1.5rem; }
  `,
})
export class DocumentDetailComponent {
  private readonly documentService = inject(DocumentService);
  private readonly commentService = inject(CommentService);
  private readonly interactionService = inject(InteractionService);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  readonly id = input.required<string>();

  protected readonly document = signal<DocumentDetail | null>(null);
  protected readonly loading = signal(true);

  protected readonly comments = signal<CommentResponse[]>([]);
  protected readonly commentsLoading = signal(true);
  protected readonly commentsLast = signal(true);
  protected readonly commentPage = signal(0);
  protected readonly commentSubmitting = signal(false);

  protected readonly commentControl = new FormControl('', [
    Validators.required,
    Validators.minLength(1),
    Validators.maxLength(2000),
  ]);

  protected readonly isOwner = computed(() => {
    const doc = this.document();
    const user = this.auth.user();
    return doc !== null && user !== null && doc.author.id === user.id;
  });

  protected readonly fileUrl = computed(() =>
    this.document() ? this.documentService.getFileUrl(this.document()!.id) : '',
  );

  constructor() {
    effect(() => {
      const id = this.id();
      this.document.set(null);
      this.loading.set(true);
      this.documentService.get(id).subscribe({
        next: doc => {
          this.document.set(doc);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
      this.logView(id);
      this.loadComments(0);
    });
  }

  protected loadComments(page: number): void {
    this.commentsLoading.set(true);
    this.commentPage.set(page);
    this.commentService.getComments(this.id(), page).subscribe({
      next: res => {
        this.comments.set(res.content);
        this.commentsLast.set(res.last);
        this.commentsLoading.set(false);
      },
      error: () => this.commentsLoading.set(false),
    });
  }

  protected addComment(): void {
    const body = this.commentControl.value?.trim();
    if (!body) return;
    this.commentSubmitting.set(true);
    this.commentService.addComment(this.id(), { body }).subscribe({
      next: () => {
        this.commentControl.reset();
        this.commentSubmitting.set(false);
        this.loadComments(0);
      },
      error: () => this.commentSubmitting.set(false),
    });
  }

  protected deleteComment(commentId: string): void {
    this.commentService.deleteComment(this.id(), commentId).subscribe(() => {
      this.loadComments(this.commentPage());
    });
  }

  protected canDeleteComment(comment: CommentResponse): boolean {
    const user = this.auth.user();
    return user !== null && comment.author.id === user.id;
  }

  protected deleteDocument(): void {
    if (!confirm('Delete this document? This cannot be undone.')) return;
    this.documentService.delete(this.id()).subscribe(() => {
      this.router.navigate(['/feed']);
    });
  }

  protected formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  private logView(id: string): void {
    if (!this.auth.isAuthenticated()) return;
    this.interactionService.logView(id).subscribe({ error: () => {} });
  }
}
