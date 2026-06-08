package com.alexandria.exception;

public abstract class ConflictException extends RuntimeException {

    private final String errorCode;

    protected ConflictException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
