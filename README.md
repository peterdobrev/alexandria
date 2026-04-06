# Alexandria REST API

Base URL: `/api`

## Auth
| Method | Endpoint | Body | Auth |
|--------|----------|------|------|
| POST | `/auth/register` | `{ email, password, displayName }` | - |
| POST | `/auth/login` | `{ email, password }` | - |

## Users
| Method | Endpoint | Body | Auth |
|--------|----------|------|------|
| GET | `/users/{id}` | - | - |
| PUT | `/users/{id}` | `{ displayName?, password? }` | Owner |
| GET | `/users/{id}/documents` | - | - |

## Documents
| Method | Endpoint | Body / Params | Auth |
|--------|----------|---------------|------|
| GET | `/documents` | `?type=&category=&author=&search=` | - |
| GET | `/documents/{id}` | - | - |
| POST | `/documents` | `{ title, description, type, fileUrl, categoryIds }` | User |
| PUT | `/documents/{id}` | `{ title?, description?, categoryIds? }` | Owner |
| DELETE | `/documents/{id}` | - | Owner |

## Categories
| Method | Endpoint | Body | Auth |
|--------|----------|------|------|
| GET | `/categories` | - | - |
| POST | `/categories` | `{ name }` | Admin |
| PUT | `/categories/{id}` | `{ name }` | Admin |
| DELETE | `/categories/{id}` | - | Admin |

## Reading Lists
| Method | Endpoint | Body | Auth |
|--------|----------|------|------|
| GET | `/reading-lists` | - | User |
| POST | `/reading-lists` | `{ name }` | User |
| GET | `/reading-lists/{id}` | - | Owner |
| PUT | `/reading-lists/{id}` | `{ name }` | Owner |
| DELETE | `/reading-lists/{id}` | - | Owner |
| POST | `/reading-lists/{id}/items` | `{ documentId }` | Owner |
| DELETE | `/reading-lists/{id}/items/{docId}` | - | Owner |

## Errors
```json
{ "error": "Not Found", "message": "Document not found", "status": 404 }
```
`400` Bad Request | `401` Unauthorized | `403` Forbidden | `404` Not Found | `409` Conflict
