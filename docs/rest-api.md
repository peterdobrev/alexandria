# Alexandria REST API

Live interactive docs: **`/swagger-ui.html`** (when the app is running)
OpenAPI JSON: **`/v3/api-docs`**

---

## Authentication

Protected endpoints require a Bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are obtained from `POST /api/auth/register` or `POST /api/auth/login`.

> **Pagination:** All paginated endpoints cap `size` at 100. Requests with `size > 100` are silently reduced to 100.

> **Privacy masking:** Private documents not owned by the authenticated user produce `404 Not Found` responses (not `403`), masking their existence.

---

## Auth

Base URL: `/api/auth`

### POST /api/auth/register

| | |
|---|---|
| Auth | None (public) |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `email` | string | required, valid email |
| `password` | string | required, min 8 chars |
| `displayName` | string | required, 2–50 chars |

**Response** `201 Created`

```json
{
  "userId": "UUID",
  "token": "string",
  "tokenType": "Bearer",
  "expiresIn": "long (seconds)"
}
```

`Location` header set to `/api/users/{userId}`

---

### POST /api/auth/login

| | |
|---|---|
| Auth | None (public) |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `email` | string | required, valid email |
| `password` | string | required |

**Response** `200 OK`

```json
{
  "userId": "UUID",
  "token": "string",
  "tokenType": "Bearer",
  "expiresIn": "long (seconds)"
}
```

---

## Users

Base URL: `/api/users`

### GET /api/users/me

| | |
|---|---|
| Auth | Authenticated |

**Response** `200 OK`

```json
{ "id": "UUID", "displayName": "string" }
```

---

### GET /api/users/{id}

| | |
|---|---|
| Auth | None (public) |

**Response** `200 OK`

```json
{ "id": "UUID", "displayName": "string" }
```

---

### PUT /api/users/{id}

| | |
|---|---|
| Auth | Must be the authenticated user themselves |
| Content-Type | `application/json` |

**Request body** (all fields optional)

| Field | Type | Constraints |
|---|---|---|
| `displayName` | string | optional; if provided must be non-blank, max 255 chars (omit or null to leave unchanged) |
| `password` | string | optional; if provided must be non-blank, 8–255 chars (omit or null to leave unchanged) |

**Response** `200 OK`

```json
{ "id": "UUID", "displayName": "string" }
```

---

## Documents

Base URL: `/api/documents`

Anonymous users may read public documents. Private documents require authentication and ownership.

### GET /api/documents

| | |
|---|---|
| Auth | Optional (anonymous allowed) |

**Query parameters**

| Param | Type | Description |
|---|---|---|
| `type` | string | Filter by document type |
| `categoryId` | UUID | Filter by category |
| `authorId` | UUID | Filter by author |
| `search` | string | Full-text search |
| `page` | int | Page number (0-based) |
| `size` | int | Page size |
| `sort` | string | `createdAt`, `updatedAt`, or `title` with optional `,asc` or `,desc` |

**Response** `200 OK` — paginated

```json
{
  "content": [
    {
      "id": "UUID",
      "title": "string",
      "description": "string",
      "type": "string",
      "visibility": "Visibility",
      "author": { "id": "UUID", "displayName": "string" },
      "categories": [{ "id": "UUID", "name": "string" }],
      "hasFile": "boolean",
      "hasBody": "boolean",
      "sizeBytes": "long",
      "contentType": "string",
      "createdAt": "Instant",
      "updatedAt": "Instant"
    }
  ],
  "page": "int",
  "size": "int",
  "totalElements": "long",
  "totalPages": "int",
  "last": "boolean"
}
```

---

### GET /api/documents/{id}

| | |
|---|---|
| Auth | Optional (anonymous allowed) |

**Response** `200 OK`

```json
{
  "id": "UUID",
  "title": "string",
  "description": "string",
  "type": "string",
  "visibility": "Visibility",
  "author": { "id": "UUID", "displayName": "string" },
  "categories": [{ "id": "UUID", "name": "string" }],
  "hasFile": "boolean",
  "hasBody": "boolean",
  "sizeBytes": "long",
  "contentType": "string",
  "body": "string",
  "createdAt": "Instant",
  "updatedAt": "Instant"
}
```

---

### POST /api/documents

Upload a file document.

| | |
|---|---|
| Auth | Authenticated |
| Content-Type | `multipart/form-data` |

**Parts**

| Part | Description |
|---|---|
| `file` | Binary file |
| `metadata` | JSON — see fields below |

**`metadata` fields**

| Field | Type | Constraints |
|---|---|---|
| `title` | string | required, max 255 chars |
| `description` | string | optional, max 5000 chars |
| `type` | string | required, max 50 chars |
| `categoryIds` | UUID[] | optional, max 20 items |
| `visibility` | Visibility | optional |

**Response** `201 Created` — `DocumentDetail` (same shape as `GET /api/documents/{id}`)
`Location` header set to absolute URL, e.g. `http://<host>/api/documents/{id}`

---

### POST /api/documents/article

Create a text/body document.

| | |
|---|---|
| Auth | Authenticated |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `title` | string | required, max 255 chars |
| `description` | string | optional, max 5000 chars |
| `type` | string | required, max 50 chars |
| `body` | string | required, max 500 000 chars |
| `categoryIds` | UUID[] | optional, max 20 items |
| `visibility` | Visibility | optional |

**Response** `201 Created` — `DocumentDetail`
`Location` header set to absolute URL, e.g. `http://<host>/api/documents/{id}`

---

### PUT /api/documents/{id}

| | |
|---|---|
| Auth | Document owner |
| Content-Type | `application/json` |

**Request body** (all fields optional)

| Field | Type | Constraints |
|---|---|---|
| `title` | string | optional, non-blank if provided, max 255 chars |
| `description` | string | optional, max 5000 chars |
| `categoryIds` | UUID[] | optional |
| `visibility` | Visibility | optional |

**Response** `200 OK` — `DocumentDetail`

---

### DELETE /api/documents/{id}

| | |
|---|---|
| Auth | Document owner or admin |

**Response** `204 No Content`

---

### GET /api/documents/{id}/file

| | |
|---|---|
| Auth | Optional (anonymous allowed) |

**Response** `200 OK` — binary file stream

Response headers include `Content-Type`, `Content-Length`, and `Content-Disposition: inline; filename=...` from stored file metadata.

---

## Categories

Base URL: `/api/categories`

### GET /api/categories

| | |
|---|---|
| Auth | None (public) |

**Response** `200 OK`

```json
[{ "id": "UUID", "name": "string" }]
```

---

### POST /api/categories

| | |
|---|---|
| Auth | Admin |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `name` | string | required, max 255 chars |

**Response** `201 Created`

```json
{ "id": "UUID", "name": "string" }
```

`Location` header set to the new category URL.

---

### PUT /api/categories/{id}

| | |
|---|---|
| Auth | Admin |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `name` | string | required, max 255 chars |

**Response** `200 OK`

```json
{ "id": "UUID", "name": "string" }
```

---

### DELETE /api/categories/{id}

| | |
|---|---|
| Auth | Admin |

**Response** `204 No Content`

---

## Reading Lists

Base URL: `/api/reading-lists`

All reading list endpoints require authentication. Access to a specific list is restricted to its owner.

### GET /api/reading-lists

| | |
|---|---|
| Auth | Authenticated |

**Query parameters**

| Param | Type | Description |
|---|---|---|
| `page` | int | Page number (0-based) |
| `size` | int | Page size |
| `sort` | string | Spring Pageable sort expression |

**Response** `200 OK` — paginated

```json
{
  "content": [
    { "id": "UUID", "name": "string", "createdAt": "Instant" }
  ],
  "page": "int",
  "size": "int",
  "totalElements": "long",
  "totalPages": "int",
  "last": "boolean"
}
```

---

### POST /api/reading-lists

| | |
|---|---|
| Auth | Authenticated |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `name` | string | required, not blank, max 255 chars |

**Response** `201 Created`

```json
{
  "id": "UUID",
  "name": "string",
  "createdAt": "Instant",
  "items": [
    {
      "id": "UUID",
      "document": "DocumentSummary",
      "addedAt": "Instant"
    }
  ]
}
```

`Location` header set to the new reading list URL.

---

### GET /api/reading-lists/{id}

| | |
|---|---|
| Auth | Reading list owner |

**Response** `200 OK` — `ReadingListResponse` (same shape as `POST /api/reading-lists` response)

---

### PUT /api/reading-lists/{id}

| | |
|---|---|
| Auth | Reading list owner |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `name` | string | required, not blank, max 255 chars |

**Response** `200 OK` — `ReadingListResponse`

---

### DELETE /api/reading-lists/{id}

| | |
|---|---|
| Auth | Reading list owner |

**Response** `204 No Content`

---

### POST /api/reading-lists/{id}/items

| | |
|---|---|
| Auth | Reading list owner |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `documentId` | UUID | required |

**Response** `201 Created`

```json
{
  "id": "UUID",
  "document": "DocumentSummary",
  "addedAt": "Instant"
}
```

`Location` header set to the new item URL: `.../items/{documentId}`

---

### DELETE /api/reading-lists/{id}/items/{documentId}

| | |
|---|---|
| Auth | Reading list owner |

**Response** `204 No Content`

---

## Comments

Base URL: `/api/documents/{documentId}/comments`

### GET /api/documents/{documentId}/comments

| | |
|---|---|
| Auth | Optional (anonymous allowed) |

**Query parameters**

| Param | Type | Description |
|---|---|---|
| `page` | int | Page number (0-based) |
| `size` | int | Page size |
| `sort` | string | Spring Pageable sort expression |

**Response** `200 OK` — paginated

```json
{
  "content": [
    {
      "id": "UUID",
      "author": { "id": "UUID", "displayName": "string" },
      "body": "string",
      "createdAt": "Instant"
    }
  ],
  "page": "int",
  "size": "int",
  "totalElements": "long",
  "totalPages": "int",
  "last": "boolean"
}
```

---

### POST /api/documents/{documentId}/comments

| | |
|---|---|
| Auth | Authenticated |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `body` | string | required, 1–2000 chars |

**Response** `201 Created`

```json
{
  "id": "UUID",
  "author": { "id": "UUID", "displayName": "string" },
  "body": "string",
  "createdAt": "Instant"
}
```

`Location` header set to the new comment URL: `.../comments/{commentId}`

---

### DELETE /api/documents/{documentId}/comments/{commentId}

| | |
|---|---|
| Auth | Comment owner or admin |

**Response** `204 No Content`

---

## Recommendations and Interactions

### GET /api/recommendations

Returns personalized document recommendations for the authenticated user.

| | |
|---|---|
| Auth | Authenticated |

**Query parameters**

| Param | Type | Constraints |
|---|---|---|
| `page` | int | max 200 |
| `size` | int | max 50 |
| `sort` | string | Spring Pageable sort expression |

**Response** `200 OK` — paginated `DocumentSummary` (same shape as `GET /api/documents`)

---

### POST /api/documents/{id}/interactions

Record a user interaction with a document.

| | |
|---|---|
| Auth | Authenticated |
| Content-Type | `application/json` |

**Request body**

| Field | Type | Constraints |
|---|---|---|
| `kind` | string | `VIEW` only — any other value returns `400 Bad Request` |

**Response** `204 No Content`

---

## Security Response Headers

All API responses include the following security headers:

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` |
| `Referrer-Policy` | `no-referrer` |
| `Content-Security-Policy` | `default-src 'none'` |

---

## Error Responses

All errors return a structured JSON body:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "human-readable description",
  "timestamp": "2024-01-01T12:00:00Z",
  "path": "/api/..."
}
```

| HTTP Status | Condition | Message |
|---|---|---|
| `400` | Validation failure (`@NotBlank`, `@Size`, etc.) | Field-level constraint message |
| `400` | Malformed JSON body | `Malformed request body` |
| `400` | Type mismatch on parameter | `Invalid value '<v>' for parameter '<name>'` |
| `400` | Missing required parameter | `Required parameter '<name>' is missing` |
| `400` | Invalid sort property | `Invalid sort property: <field>` |
| `400` | Invalid multipart request | `Invalid multipart request` |
| `401` | Missing or invalid token | `Authentication required` |
| `401` | Expired JWT | `JWT token has expired` |
| `401` | Malformed JWT | `JWT token is invalid or malformed` |
| `401` | Token for deleted user | `Authentication required` |
| `403` | Authenticated but insufficient permissions | `Access denied` |
| `404` | Resource not found | `Resource not found` |
| `405` | HTTP method not allowed | Method-specific message; `Allow` header lists permitted methods |
| `415` | Unsupported content type | `Accept` header lists supported types |

> **JWT compatibility:** Tokens include `iss`, `aud`, and `jti` claims validated on every request. Tokens issued by a previous server version will be rejected with `401`.
