package com.alexandria.exception;

import java.util.UUID;

public class CommentNotFoundException extends NotFoundException {

    public CommentNotFoundException(UUID id) {
        super("Comment not found: " + id, "COMMENT_NOT_FOUND");
    }
}
