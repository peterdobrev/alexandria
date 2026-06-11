# Database Schema

![Database Schema](../alexandria-db.svg?raw=true)

## Enums

| Column | Values |
|---|---|
| `documents.type` | `BOOK`, `ARTICLE`, `PAPER` |
| `documents.visibility` | `PUBLIC`, `PRIVATE` |
| `user_document_interactions.kind` | `VIEW`, `BOOKMARK` |
