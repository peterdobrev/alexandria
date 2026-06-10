export type Visibility = 'PUBLIC' | 'PRIVATE';

export interface AuthorSummary {
  id: string;
  displayName: string;
}

export interface CategorySummary {
  id: string;
  name: string;
}

export interface DocumentSummary {
  id: string;
  title: string;
  description: string | null;
  type: string;
  visibility: Visibility;
  author: AuthorSummary;
  categories: CategorySummary[];
  hasFile: boolean;
  hasBody: boolean;
  sizeBytes: number | null;
  contentType: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentDetail extends DocumentSummary {
  body: string | null;
}

export interface CreateDocumentRequest {
  title: string;
  description?: string;
  type: string;
  categoryIds?: string[];
  visibility?: Visibility;
}

export interface CreateArticleRequest {
  title: string;
  description?: string;
  type: string;
  body: string;
  categoryIds?: string[];
  visibility?: Visibility;
}

export interface UpdateDocumentRequest {
  title?: string;
  description?: string;
  categoryIds?: string[];
  visibility?: Visibility;
}
