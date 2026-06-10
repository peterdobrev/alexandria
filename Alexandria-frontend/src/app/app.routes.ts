import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: '/feed', pathMatch: 'full' },
  {
    path: 'feed',
    loadComponent: () => import('./features/feed/feed.component').then(m => m.FeedComponent),
  },
  {
    path: 'explore',
    loadComponent: () =>
      import('./features/documents/document-list/document-list.component').then(
        m => m.DocumentListComponent,
      ),
  },
  {
    path: 'auth',
    loadChildren: () => import('./features/auth/auth.routes').then(m => m.AUTH_ROUTES),
  },
  {
    path: 'documents',
    loadChildren: () =>
      import('./features/documents/documents.routes').then(m => m.DOCUMENT_ROUTES),
  },
  {
    path: 'reading-lists',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/reading-lists/reading-lists.routes').then(m => m.READING_LIST_ROUTES),
  },
  {
    path: 'users',
    loadChildren: () => import('./features/users/users.routes').then(m => m.USER_ROUTES),
  },
  { path: '**', redirectTo: '/feed' },
];
