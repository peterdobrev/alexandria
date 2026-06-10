import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class InteractionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/documents`;

  /** Logs a VIEW interaction, which feeds the recommendation engine. */
  logView(documentId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${documentId}/interactions`, { kind: 'VIEW' });
  }
}
