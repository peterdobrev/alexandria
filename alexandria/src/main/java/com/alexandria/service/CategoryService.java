package com.alexandria.service;

import com.alexandria.dto.category.CategoryResponse;
import com.alexandria.dto.category.CreateCategoryRequest;
import com.alexandria.dto.category.UpdateCategoryRequest;
import com.alexandria.entity.Category;
import com.alexandria.exception.CategoryAlreadyExistsException;
import com.alexandria.exception.CategoryNotFoundException;
import com.alexandria.mapper.CategoryMapper;
import com.alexandria.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        return categoryRepository.findAll().stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    public CategoryResponse create(CreateCategoryRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            log.warn("Attempt to create duplicate category: {}", request.name());
            throw new CategoryAlreadyExistsException(request.name());
        }

        Category category = categoryMapper.fromCreate(request);
        Category saved = categoryRepository.save(category);
        log.info("Category created: {} ({})", saved.getName(), saved.getId());
        return categoryMapper.toResponse(saved);
    }

    public CategoryResponse update(UUID id, UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        if (!category.getName().equals(request.name())
                && categoryRepository.existsByName(request.name())) {
            log.warn("Attempt to rename category {} to existing name: {}", id, request.name());
            throw new CategoryAlreadyExistsException(request.name());
        }

        category.setName(request.name());
        Category saved = categoryRepository.save(category);
        log.info("Category updated: {} ({})", saved.getName(), saved.getId());
        return categoryMapper.toResponse(saved);
    }

    public void delete(UUID id) {
        if (!categoryRepository.existsById(id)) {
            throw new CategoryNotFoundException(id);
        }
        categoryRepository.deleteById(id);
        log.info("Category deleted: {}", id);
    }
}
