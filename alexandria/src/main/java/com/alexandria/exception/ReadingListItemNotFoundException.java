package com.alexandria.exception;

import java.util.UUID;

public class ReadingListItemNotFoundException extends NotFoundException {

    private static final String ERROR_CODE = "READING_LIST_ITEM_NOT_FOUND";

    public ReadingListItemNotFoundException(UUID listId, UUID docId) {
        super("Item not found in reading list: list=" + listId + ", doc=" + docId);
    }

    @Override
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
