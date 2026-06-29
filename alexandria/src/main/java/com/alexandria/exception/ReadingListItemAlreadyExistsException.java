package com.alexandria.exception;

import java.util.UUID;

public class ReadingListItemAlreadyExistsException extends ConflictException {

    public ReadingListItemAlreadyExistsException(UUID listId, UUID documentId) {
        super("Document " + documentId + " is already in reading list " + listId);
    }
}
