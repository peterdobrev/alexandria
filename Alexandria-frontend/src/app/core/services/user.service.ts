import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';
import type { UserSummary, UpdateUserRequest } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/users`;

  getUser(id: string): Observable<UserSummary> {
    return this.http.get<UserSummary>(`${this.base}/${id}`);
  }

  updateUser(id: string, request: UpdateUserRequest): Observable<UserSummary> {
    return this.http.put<UserSummary>(`${this.base}/${id}`, request);
  }
}
