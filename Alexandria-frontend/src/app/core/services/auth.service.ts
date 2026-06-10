import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { tap, catchError } from 'rxjs/operators';
import { EMPTY } from 'rxjs';
import { environment } from '../../../environments/environment';
import type { AuthResponse, LoginRequest, RegisterRequest } from '../models/auth.model';
import type { UserSummary } from '../models/user.model';
import type { Observable } from 'rxjs';

const TOKEN_KEY = 'auth_token';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly token = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  private readonly currentUser = signal<UserSummary | null>(null);

  readonly isAuthenticated = computed(() => this.token() !== null);
  readonly user = this.currentUser.asReadonly();

  constructor() {
    if (this.token()) {
      // Defer to a microtask so AuthService finishes constructing before this
      // HTTP call runs the interceptor (which injects AuthService). Otherwise the
      // re-entrant injection throws a circular-dependency error that gets swallowed
      // here and wipes the token — logging the user out on every page load.
      queueMicrotask(() => this.fetchCurrentUser().subscribe());
    }
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${environment.apiUrl}/auth/login`, request).pipe(
      tap(response => this.storeToken(response.token)),
    );
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${environment.apiUrl}/auth/register`, request).pipe(
      tap(response => this.storeToken(response.token)),
    );
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    this.token.set(null);
    this.currentUser.set(null);
    this.router.navigate(['/auth/login']);
  }

  getToken(): string | null {
    return this.token();
  }

  private storeToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
    this.token.set(token);
    this.fetchCurrentUser().subscribe();
  }

  private fetchCurrentUser(): Observable<UserSummary> {
    return this.http.get<UserSummary>(`${environment.apiUrl}/users/me`).pipe(
      tap(user => this.currentUser.set(user)),
      catchError((err: unknown) => {
        // Only drop the session on a genuine auth failure, not on transient errors.
        if (err instanceof HttpErrorResponse && err.status === 401) {
          localStorage.removeItem(TOKEN_KEY);
          this.token.set(null);
        }
        return EMPTY;
      }),
    );
  }
}
