package com.alexandria.exception;

import java.util.UUID;

public class CategoryNotFoundException extends NotFoundException {

    public CategoryNotFoundException(UUID id) {
        super("Category not found: " + id, "CATEGORY_NOT_FOUND");
    }
}
