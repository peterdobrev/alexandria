# Database Schema

## Tables

```dbml
Table users {
  id            uuid          [pk, not null]
  email         varchar(320)  [not null, unique]
  password_hash varchar(255)  [not null]
  display_name  varchar(255)
  created_at    timestamptz   [not null]
}

Table documents {
  id                uuid         [pk, not null]
  title             varchar(255) [not null]
  description       text
  type              varchar(50)  [not null, note: 'e.g. BOOK, ARTICLE, PAPER']
  visibility        varchar(20)  [not null, default: 'PUBLIC', note: 'PUBLIC | PRIVATE']
  file_url          varchar(255)
  uploaded_file_path varchar(512)
  original_filename varchar(255)
  content_type      varchar(100)
  size_bytes        bigint
  body              text
  author_id         uuid         [ref: > users.id, note: 'FK fk_documents_author, ON DELETE SET NULL']
  created_at        timestamptz
  updated_at        timestamptz
}

Table categories {
  id   uuid         [pk, not null]
  name varchar(255) [not null, unique]
}

Table document_categories {
  document_id uuid [pk, not null, ref: > documents.id, note: 'FK fk_doc_categories_document, ON DELETE CASCADE']
  category_id uuid [pk, not null, ref: > categories.id, note: 'FK fk_doc_categories_category, ON DELETE CASCADE']
}

Table reading_lists {
  id         uuid         [pk, not null]
  user_id    uuid         [not null, ref: > users.id, note: 'FK fk_reading_lists_user, ON DELETE CASCADE']
  name       varchar(255) [not null]
  created_at timestamptz  [not null]
}

Table reading_list_items {
  id          uuid        [pk, not null]
  list_id     uuid        [not null, ref: > reading_lists.id, note: 'FK fk_reading_list_items_list, ON DELETE CASCADE']
  document_id uuid        [not null, ref: > documents.id, note: 'FK fk_reading_list_items_document, ON DELETE CASCADE']
  added_at    timestamptz [not null]
}

Table comments {
  id          uuid        [pk, not null]
  document_id uuid        [not null, ref: > documents.id, note: 'FK fk_comments_document, ON DELETE CASCADE']
  author_id   uuid        [not null, ref: > users.id, note: 'FK fk_comments_author, ON DELETE CASCADE']
  body        text        [not null]
  created_at  timestamptz
}

Table roles {
  id   uuid        [pk, not null]
  name varchar(50) [not null, unique]
}

Table user_roles {
  user_id uuid [pk, not null, ref: > users.id, note: 'FK fk_user_roles_user, ON DELETE CASCADE']
  role_id uuid [pk, not null, ref: > roles.id, note: 'FK fk_user_roles_role, ON DELETE CASCADE']
}

Table user_document_interactions {
  id            uuid        [pk, not null]
  user_id       uuid        [not null, ref: > users.id, note: 'FK fk_udi_user, ON DELETE CASCADE']
  document_id   uuid        [not null, ref: > documents.id, note: 'FK fk_udi_document, ON DELETE CASCADE']
  kind          varchar(16) [not null, note: 'CHECK (kind IN (''VIEW'',''BOOKMARK''))']
  interacted_at timestamptz [not null]
}
```

## Enums

| Column | Values |
|---|---|
| `documents.type` | `BOOK`, `ARTICLE`, `PAPER` |
| `documents.visibility` | `PUBLIC`, `PRIVATE` |
| `user_document_interactions.kind` | `VIEW`, `BOOKMARK` |
