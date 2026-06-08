package com.alexandria.exception;

public abstract class ForbiddenException extends RuntimeException {

    private final String errorCode;

    protected ForbiddenException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
