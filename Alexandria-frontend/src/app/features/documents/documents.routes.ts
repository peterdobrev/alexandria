import { Routes } from '@angular/router';
import { authGuard } from '../../core/guards/auth.guard';

export const DOCUMENT_ROUTES: Routes = [
  { path: '', redirectTo: '/explore', pathMatch: 'full' },
  {
    path: 'create',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./document-create/document-create.component').then(m => m.DocumentCreateComponent),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./document-detail/document-detail.component').then(m => m.DocumentDetailComponent),
  },
  {
    path: ':id/edit',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./document-edit/document-edit.component').then(m => m.DocumentEditComponent),
  },
];
