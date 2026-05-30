package com.alexandria.exception;

import java.util.UUID;

public class ReadingListNotFoundException extends ResourceNotFoundException {

    public ReadingListNotFoundException(UUID id) {
        super("Reading list not found: " + id);
    }
}
