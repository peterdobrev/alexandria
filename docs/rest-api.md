# Alexandria REST API

Live, interactive docs: **`/swagger-ui.html`** (when the app is running)
OpenAPI JSON: **`/v3/api-docs`**

This page lists endpoint groups; for request/response details see Swagger.

## Auth (Phase 0)
- POST /api/auth/register
- POST /api/auth/login

## Users (Phase 1, minimal)
- GET  /api/users/{id}
- PUT  /api/users/{id}                        Owner

## Documents (Phase 1)
- GET    /api/documents                       Public
- GET    /api/documents/{id}                  Visibility-aware
- GET    /api/documents/{id}/file             Visibility-aware
- POST   /api/documents       multipart       Auth
- POST   /api/documents/article  JSON         Auth
- PUT    /api/documents/{id}                  Owner
- DELETE /api/documents/{id}                  Owner | Admin

## Categories (Phase 1)
- GET    /api/categories                      Public
- POST   /api/categories                      Admin
- PUT    /api/categories/{id}                 Admin
- DELETE /api/categories/{id}                 Admin

## Reading Lists (Phase 2)
- GET    /api/reading-lists                          Auth (own lists)
- POST   /api/reading-lists                          Auth
- GET    /api/reading-lists/{id}                     Owner
- PUT    /api/reading-lists/{id}                     Owner
- DELETE /api/reading-lists/{id}                     Owner
- POST   /api/reading-lists/{id}/items               Owner
- DELETE /api/reading-lists/{id}/items/{documentId}  Owner

## Comments (Phase 3)
- GET    /api/documents/{documentId}/comments        Visibility-aware
- POST   /api/documents/{documentId}/comments        Auth
- DELETE /api/documents/{documentId}/comments/{commentId}  Owner | Admin

## Recommendations (Phase 4)
- POST   /api/documents/{id}/interactions    Auth   ({ kind: VIEW })
- GET    /api/recommendations                Auth
