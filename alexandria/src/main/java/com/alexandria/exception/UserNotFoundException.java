package com.alexandria.exception;

import java.util.UUID;

public class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(UUID id) {
        super("User not found: " + id, "USER_NOT_FOUND");
    }
}
