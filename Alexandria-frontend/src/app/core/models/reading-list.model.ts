import type { DocumentSummary } from './document.model';

export interface ReadingListSummaryResponse {
  id: string;
  name: string;
  createdAt: string;
}

export interface ReadingListItemResponse {
  id: string;
  document: DocumentSummary;
  addedAt: string;
}

export interface ReadingListResponse {
  id: string;
  name: string;
  createdAt: string;
  items: ReadingListItemResponse[];
}

export interface CreateReadingListRequest {
  name: string;
}

export interface UpdateReadingListRequest {
  name: string;
}

export interface AddReadingListItemRequest {
  documentId: string;
}
