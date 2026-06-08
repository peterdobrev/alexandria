package com.alexandria.exception;

import java.util.UUID;

public class ReadingListNotFoundException extends NotFoundException {

    public ReadingListNotFoundException(UUID id) {
        super("Reading list not found: " + id, "READING_LIST_NOT_FOUND");
    }
}
