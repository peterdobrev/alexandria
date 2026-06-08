package com.alexandria.exception;

import lombok.Getter;

@Getter
public abstract class NotFoundException extends RuntimeException {

    private final String errorCode;

    protected NotFoundException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
