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

## Reading Lists (Phase 2 — see sub-spec)
## Annotations (Phase 4 — see sub-spec)
## Recommendations (Phase 5 — see sub-spec)
