package com.alexandria.exception;

public class EmailAlreadyInUseException extends ConflictException {

    public EmailAlreadyInUseException(String email) {
        super("Email already in use: " + email, "EMAIL_TAKEN");
    }
}
