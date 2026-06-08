package com.alexandria.exception;

public class InvalidDocumentContentException extends IllegalArgumentException {

    public InvalidDocumentContentException(String message) {
        super(message);
    }
}
