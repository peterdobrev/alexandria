package com.alexandria.exception;

import java.util.UUID;

public class ReadingListItemNotFoundException extends NotFoundException {

    public ReadingListItemNotFoundException(UUID listId, UUID docId) {
        super("Item not found in reading list: list=" + listId + ", doc=" + docId,
                "READING_LIST_ITEM_NOT_FOUND");
    }
}
