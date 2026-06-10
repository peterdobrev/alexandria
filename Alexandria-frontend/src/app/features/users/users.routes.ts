import { Routes } from '@angular/router';

export const USER_ROUTES: Routes = [
  {
    path: ':id',
    loadComponent: () =>
      import('./user-profile/user-profile.component').then(m => m.UserProfileComponent),
  },
];
