package com.alexandria.exception;

public class AccessForbiddenException extends ForbiddenException {

    public AccessForbiddenException(String message) {
        super(message, "ACCESS_FORBIDDEN");
    }
}
