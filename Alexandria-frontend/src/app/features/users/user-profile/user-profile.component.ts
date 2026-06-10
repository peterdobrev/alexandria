import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { DocumentService } from '../../../core/services/document.service';
import { UserService } from '../../../core/services/user.service';
import { AvatarComponent } from '../../../shared/ui/avatar.component';
import { DocumentCardComponent } from '../../../shared/ui/document-card.component';
import { CardSkeletonComponent } from '../../../shared/ui/card-skeleton.component';
import type { DocumentSummary } from '../../../core/models/document.model';
import type { UserSummary } from '../../../core/models/user.model';

@Component({
  selector: 'app-user-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AvatarComponent, DocumentCardComponent, CardSkeletonComponent],
  template: `
    @if (loading()) {
      <div class="state">Loading…</div>
    } @else {
      @if (user(); as profile) {
      <header class="card profile-head">
        <app-avatar [name]="profile.displayName" [size]="72" />
        <div class="profile-meta">
          <div class="name-row">
            <h1>{{ profile.displayName }}</h1>
            @if (isSelf()) {
              <span class="chip">You</span>
            }
          </div>
          <p class="text-muted count">{{ postCount() }} {{ postCount() === 1 ? 'post' : 'posts' }}</p>
        </div>
        @if (isSelf()) {
          <a routerLink="/documents/create" class="btn btn-primary">Compose</a>
        }
      </header>

      <h2 class="section-title">Posts</h2>

      @if (docsLoading()) {
        <div class="list">
          <app-card-skeleton />
          <app-card-skeleton />
        </div>
      } @else if (documents().length === 0) {
        <div class="state">
          <p class="text-secondary">No posts published yet.</p>
        </div>
      } @else {
        <div class="list">
          @for (doc of documents(); track doc.id) {
            <app-document-card [document]="doc" />
          }
        </div>

        <div class="pagination">
          <button type="button" class="btn btn-ghost" [disabled]="page() === 0" (click)="loadDocs(page() - 1)">
            ← Prev
          </button>
          <span class="text-muted">Page {{ page() + 1 }}</span>
          <button type="button" class="btn btn-ghost" [disabled]="isLastPage()" (click)="loadDocs(page() + 1)">
            Next →
          </button>
        </div>
      }
      } @else {
        <div class="state">User not found.</div>
      }
    }
  `,
  styles: `
    .state { text-align: center; padding: 4rem 1rem; color: var(--color-text-muted); }
    .profile-head {
      display: flex;
      align-items: center;
      gap: 1.25rem;
      padding: 1.5rem;
      margin-bottom: 1.5rem;
    }
    .profile-meta { flex: 1; }
    .name-row { display: flex; align-items: center; gap: 0.6rem; }
    h1 { margin: 0; font-size: 1.5rem; }
    .count { margin: 0.25rem 0 0; font-size: 0.9rem; }
    .section-title { font-size: 1.15rem; margin: 0 0 1rem; }
    .list { display: flex; flex-direction: column; gap: 1rem; }
    .pagination { display: flex; justify-content: center; align-items: center; gap: 1rem; margin-top: 1.5rem; }
  `,
})
export class UserProfileComponent {
  private readonly userService = inject(UserService);
  private readonly documentService = inject(DocumentService);
  protected readonly auth = inject(AuthService);

  readonly id = input.required<string>();

  protected readonly user = signal<UserSummary | null>(null);
  protected readonly loading = signal(true);

  protected readonly documents = signal<DocumentSummary[]>([]);
  protected readonly docsLoading = signal(true);
  protected readonly page = signal(0);
  protected readonly isLastPage = signal(true);
  protected readonly postCount = signal(0);

  protected readonly isSelf = computed(() => this.auth.user()?.id === this.id());

  constructor() {
    effect(() => {
      const id = this.id();
      this.loading.set(true);
      this.userService.getUser(id).subscribe({
        next: u => {
          this.user.set(u);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
      this.loadDocs(0);
    });
  }

  protected loadDocs(page: number): void {
    this.docsLoading.set(true);
    this.page.set(page);
    this.documentService.list({ authorId: this.id(), sort: 'createdAt,desc', page, size: 10 }).subscribe({
      next: res => {
        this.documents.set(res.content);
        this.isLastPage.set(res.last);
        this.postCount.set(res.totalElements);
        this.docsLoading.set(false);
      },
      error: () => this.docsLoading.set(false),
    });
  }
}
