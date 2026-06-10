import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import type { Observable } from 'rxjs';
import type { SpringPage } from '../models/page.model';
import type { CommentResponse, CreateCommentRequest } from '../models/comment.model';

@Injectable({ providedIn: 'root' })
export class CommentService {
  private readonly http = inject(HttpClient);

  getComments(documentId: string, page = 0, size = 10): Observable<SpringPage<CommentResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<SpringPage<CommentResponse>>(
      `${environment.apiUrl}/documents/${documentId}/comments`,
      { params },
    );
  }

  addComment(documentId: string, request: CreateCommentRequest): Observable<CommentResponse> {
    return this.http.post<CommentResponse>(
      `${environment.apiUrl}/documents/${documentId}/comments`,
      request,
    );
  }

  deleteComment(documentId: string, commentId: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.apiUrl}/documents/${documentId}/comments/${commentId}`,
    );
  }
}
