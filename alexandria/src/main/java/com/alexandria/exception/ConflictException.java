package com.alexandria.exception;

import lombok.Getter;

@Getter
public abstract class ConflictException extends RuntimeException {

    private final String errorCode;

    protected ConflictException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
