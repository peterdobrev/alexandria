import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';
import type { PageResponse } from '../models/page.model';
import type { DocumentSummary } from '../models/document.model';

@Injectable({ providedIn: 'root' })
export class RecommendationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/recommendations`;

  list(page = 0, size = 10): Observable<PageResponse<DocumentSummary>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<DocumentSummary>>(this.base, { params });
  }
}
