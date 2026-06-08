package com.alexandria.mapper;

import com.alexandria.dto.category.CategoryResponse;
import com.alexandria.dto.category.CreateCategoryRequest;
import com.alexandria.entity.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }

    public Category fromCreate(CreateCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name());
        return category;
    }
}
