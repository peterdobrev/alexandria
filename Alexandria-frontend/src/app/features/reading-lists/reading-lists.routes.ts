import { Routes } from '@angular/router';

export const READING_LIST_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./reading-list-list/reading-list-list.component').then(
        m => m.ReadingListListComponent,
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./reading-list-detail/reading-list-detail.component').then(
        m => m.ReadingListDetailComponent,
      ),
  },
];
