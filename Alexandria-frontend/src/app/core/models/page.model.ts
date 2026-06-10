export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  last: boolean;
  size: number;
  number: number;
}
