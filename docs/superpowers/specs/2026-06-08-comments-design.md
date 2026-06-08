# Comments Feature Design

**Date:** 2026-06-08  
**Phase:** 4 (replaces Annotations)  
**Status:** Approved

## Overview

Flat, public comments on documents. Any authenticated user can comment on any document they can view. Authors can delete their own comments; admins can delete any comment. No replies, no likes, no editing.

## Data Model

### Entity: `Comment`

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | PK, generated |
| `document` | `@ManyToOne Document` | FK `document_id`, not null |
| `author` | `@ManyToOne User` | FK `author_id`, not null |
| `body` | text | not null, 1–2000 chars |
| `createdAt` | Instant | set on persist |

### DB Migration

New Liquibase changeset `007-add-comments.yaml`:
- Creates `comments` table with above columns
- FK constraints to `documents(id)` and `users(id)`
- Non-unique index on `document_id`

### Relationship additions

- `Document` — `@OneToMany(mappedBy = "document", cascade = ALL, orphanRemoval = true) List<Comment> comments`
- `User` — `@OneToMany(mappedBy = "author", cascade = ALL, orphanRemoval = true) List<Comment> comments`

## API

| Method | Path | Auth | Response |
|---|---|---|---|
| `GET` | `/api/documents/{id}/comments` | Public (visibility-aware) | `200 Page<CommentResponse>` |
| `POST` | `/api/documents/{id}/comments` | Authenticated | `201 CommentResponse` |
| `DELETE` | `/api/documents/{id}/comments/{commentId}` | Owner or Admin | `204` |

### DTOs

**`CreateCommentRequest`**
- `@NotBlank @Size(min=1, max=2000) String body`

**`CommentResponse`**
- `UUID id`
- `AuthorSummary author` (reuse existing `dto/document/AuthorSummary.java`)
- `String body`
- `Instant createdAt`

### Authorization

- `DELETE` uses `@PreAuthorize("@ownership.isCommentOwnerOrAdmin(#commentId, principal)")` — consistent with existing ownership pattern
- `GET` and `POST` verify the requesting user can view the document (reuse existing visibility logic): unauthenticated users can only comment on/see comments for PUBLIC documents; authenticated users can also access their own PRIVATE documents

## Components

### `CommentRepository`

Extends `JpaRepository<Comment, UUID>`. Custom method:
- `Page<Comment> findByDocumentId(UUID documentId, Pageable pageable)`

### `CommentService`

- `getComments(UUID documentId, Pageable pageable)` — verifies document exists and is visible to current user, returns paginated `CommentResponse`
- `addComment(UUID documentId, CreateCommentRequest request, User currentUser)` — verifies document exists and is visible, creates and saves comment, returns `CommentResponse`
- `deleteComment(UUID documentId, UUID commentId)` — verifies comment belongs to document, deletes it

### `CommentMapper`

- `toResponse(Comment comment)` — maps to `CommentResponse`, reuses `UserMapper.toAuthorSummary()`

### `OwnershipService` addition

- `isCommentOwnerOrAdmin(UUID commentId, Object principal)` — loads comment, checks `author.id == currentUserId || currentUser.hasRole(ADMIN)`

### `CommentNotFoundException`

Extends `NotFoundException` — consistent with `DocumentNotFoundException`, `ReadingListNotFoundException`, etc.

## Testing

### `CommentServiceTest` (unit, mocked repositories)

- `getComments` returns paginated results for a visible document
- `getComments` throws `DocumentNotFoundException` for unknown document
- `addComment` creates and returns a comment for a visible document
- `addComment` throws `DocumentNotFoundException` for unknown document
- `deleteComment` deletes successfully when comment belongs to document
- `deleteComment` throws `CommentNotFoundException` for unknown comment

### `CommentControllerTest` (`@WebMvcTest`, mocked service)

- `GET` returns `200` with paginated comments for a public document
- `POST` returns `201` with created comment for authenticated user
- `POST` returns `401` for unauthenticated request
- `DELETE` returns `204` for comment owner
- `DELETE` returns `403` for non-owner non-admin
