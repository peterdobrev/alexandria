package com.alexandria.exception;

import java.util.UUID;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(UUID id) {
        super("User not found: " + id);
    }
}
