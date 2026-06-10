import type { AuthorSummary } from './document.model';

export interface CommentResponse {
  id: string;
  author: AuthorSummary;
  body: string;
  createdAt: string;
}

export interface CreateCommentRequest {
  body: string;
}
