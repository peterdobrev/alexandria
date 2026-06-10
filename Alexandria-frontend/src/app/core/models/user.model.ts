export interface UserSummary {
  id: string;
  displayName: string;
}

export interface UpdateUserRequest {
  displayName?: string;
  password?: string;
}
