package com.alexandria.exception;

import java.util.UUID;

public class DocumentNotFoundException extends NotFoundException {

    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id, "DOCUMENT_NOT_FOUND");
    }
}
