package com.alexandria.exception;

import lombok.Getter;

@Getter
public abstract class ForbiddenException extends RuntimeException {

    private final String errorCode;

    protected ForbiddenException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
