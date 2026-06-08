package com.alexandria.exception;

public class InvalidDocumentContentException extends ConflictException {

    public InvalidDocumentContentException(String message) {
        super(message, "INVALID_DOCUMENT_CONTENT");
    }
}
