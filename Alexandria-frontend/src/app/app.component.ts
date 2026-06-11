import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { TopBarComponent } from './shared/layout/top-bar.component';
import { SideNavComponent } from './shared/layout/side-nav.component';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, TopBarComponent, SideNavComponent],
  template: `
    <app-top-bar [isAuthPage]="isAuthPage()" />
    <div class="app-body">
      @if (!isAuthPage()) {
        <aside class="app-rail">
          <app-side-nav />
        </aside>
      }
      <main class="app-main" [class.app-main--auth]="isAuthPage()">
        <router-outlet />
      </main>
    </div>
  `,
  styles: `
    .app-rail {
      position: fixed;
      top: var(--header-height);
      left: 0;
      bottom: 0;
      width: var(--sidebar-width);
      padding: 1.25rem 0 2rem;
      overflow-y: auto;
    }
    .app-main {
      margin-left: var(--sidebar-width);
      padding: 1.25rem 2rem 2rem;
      min-width: 0;
      max-width: calc(var(--sidebar-width) + 860px);
    }
    .app-main--auth {
      margin-left: 0;
      max-width: 100%;
      min-height: calc(100vh - var(--header-height));
      padding: 2rem 1rem;
      display: flex;
      flex-direction: column;
    }
    @media (max-width: 860px) {
      .app-rail { display: none; }
      .app-main { margin-left: 0; padding-bottom: 5rem; }
      .app-main--auth { padding-bottom: 2rem; }
    }
  `,
})
export class AppComponent {
  private readonly router = inject(Router);
  protected readonly isAuthPage = signal(this.router.url.startsWith('/auth'));

  constructor() {
    this.router.events
      .pipe(
        filter(e => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(e => this.isAuthPage.set((e as NavigationEnd).urlAfterRedirects.startsWith('/auth')));
  }
}
