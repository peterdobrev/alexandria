import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.getToken();

  const outgoing = token
    ? req.clone({ headers: req.headers.set('Authorization', `Bearer ${token}`) })
    : req;

  return next(outgoing).pipe(
    catchError(err => {
      if (err instanceof HttpErrorResponse && err.status === 401 && token && !req.url.includes('/auth/')) {
        auth.logout();
      }
      return throwError(() => err);
    }),
  );
};
