import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TopBarComponent } from './shared/layout/top-bar.component';
import { SideNavComponent } from './shared/layout/side-nav.component';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, TopBarComponent, SideNavComponent],
  template: `
    <app-top-bar />
    <div class="app-body">
      <aside class="app-rail">
        <app-side-nav />
      </aside>
      <main class="app-main">
        <router-outlet />
      </main>
    </div>
  `,
  styles: `
    .app-body {
      display: flex;
      gap: 1.5rem;
      max-width: 1100px;
      margin: 0 auto;
      padding: 1.25rem 1rem 2rem;
      align-items: flex-start;
    }
    .app-rail {
      flex-shrink: 0;
    }
    .app-main {
      flex: 1;
      min-width: 0;
      max-width: var(--content-max);
      margin: 0 auto;
    }
    @media (max-width: 860px) {
      .app-rail { display: none; }
      .app-body { padding-bottom: 5rem; }
    }
  `,
})
export class AppComponent {}
