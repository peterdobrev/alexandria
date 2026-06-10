import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';
import type { SpringPage } from '../models/page.model';
import type {
  ReadingListResponse,
  ReadingListSummaryResponse,
  ReadingListItemResponse,
  CreateReadingListRequest,
  UpdateReadingListRequest,
  AddReadingListItemRequest,
} from '../models/reading-list.model';

@Injectable({ providedIn: 'root' })
export class ReadingListService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/reading-lists`;

  getReadingLists(page = 0, size = 20): Observable<SpringPage<ReadingListSummaryResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<SpringPage<ReadingListSummaryResponse>>(this.base, { params });
  }

  createReadingList(request: CreateReadingListRequest): Observable<ReadingListResponse> {
    return this.http.post<ReadingListResponse>(this.base, request);
  }

  getReadingList(id: string): Observable<ReadingListResponse> {
    return this.http.get<ReadingListResponse>(`${this.base}/${id}`);
  }

  updateReadingList(id: string, request: UpdateReadingListRequest): Observable<ReadingListResponse> {
    return this.http.put<ReadingListResponse>(`${this.base}/${id}`, request);
  }

  deleteReadingList(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  addItem(id: string, request: AddReadingListItemRequest): Observable<ReadingListItemResponse> {
    return this.http.post<ReadingListItemResponse>(`${this.base}/${id}/items`, request);
  }

  removeItem(id: string, documentId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}/items/${documentId}`);
  }
}
