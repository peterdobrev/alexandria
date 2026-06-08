package com.alexandria.exception;

public class CategoryAlreadyExistsException extends ConflictException {

    public CategoryAlreadyExistsException(String name) {
        super("Category already exists: " + name, "CATEGORY_NAME_TAKEN");
    }
}
