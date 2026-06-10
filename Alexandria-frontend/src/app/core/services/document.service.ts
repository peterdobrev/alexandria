import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';
import type { PageResponse } from '../models/page.model';
import type {
  DocumentDetail,
  DocumentSummary,
  CreateDocumentRequest,
  CreateArticleRequest,
  UpdateDocumentRequest,
} from '../models/document.model';

export interface DocumentFilters {
  type?: string;
  categoryId?: string;
  authorId?: string;
  search?: string;
  page?: number;
  size?: number;
  sort?: string;
}

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/documents`;

  list(filters: DocumentFilters = {}): Observable<PageResponse<DocumentSummary>> {
    let params = new HttpParams();
    if (filters.type) params = params.set('type', filters.type);
    if (filters.categoryId) params = params.set('categoryId', filters.categoryId);
    if (filters.authorId) params = params.set('authorId', filters.authorId);
    if (filters.search) params = params.set('search', filters.search);
    if (filters.page !== undefined) params = params.set('page', filters.page);
    if (filters.size !== undefined) params = params.set('size', filters.size);
    if (filters.sort) params = params.set('sort', filters.sort);
    return this.http.get<PageResponse<DocumentSummary>>(this.base, { params });
  }

  get(id: string): Observable<DocumentDetail> {
    return this.http.get<DocumentDetail>(`${this.base}/${id}`);
  }

  create(metadata: CreateDocumentRequest, file: File): Observable<DocumentDetail> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    return this.http.post<DocumentDetail>(this.base, formData);
  }

  createArticle(request: CreateArticleRequest): Observable<DocumentDetail> {
    return this.http.post<DocumentDetail>(`${this.base}/article`, request);
  }

  update(id: string, request: UpdateDocumentRequest): Observable<DocumentDetail> {
    return this.http.put<DocumentDetail>(`${this.base}/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  getFileUrl(id: string): string {
    return `${this.base}/${id}/file`;
  }
}
