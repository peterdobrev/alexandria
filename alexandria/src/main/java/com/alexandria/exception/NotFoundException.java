package com.alexandria.exception;

public abstract class NotFoundException extends RuntimeException {

    private final String errorCode;

    protected NotFoundException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
