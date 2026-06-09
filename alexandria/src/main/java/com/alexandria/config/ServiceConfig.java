package com.alexandria.config;

import com.alexandria.mapper.CategoryMapper;
import com.alexandria.mapper.CommentMapper;
import com.alexandria.mapper.DocumentMapper;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.mapper.UserMapper;
import com.alexandria.repository.CategoryRepository;
import com.alexandria.repository.CommentRepository;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.InteractionRepository;
import com.alexandria.repository.JpaRecommendationQueryRunner;
import com.alexandria.repository.ReadingListItemRepository;
import com.alexandria.repository.ReadingListRepository;
import com.alexandria.repository.RecommendationQueryRunner;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.CategoryService;
import com.alexandria.service.CommentService;
import com.alexandria.service.DocumentService;
import com.alexandria.service.InteractionService;
import com.alexandria.service.OwnershipService;
import com.alexandria.service.ReadingListService;
import com.alexandria.service.RecommendationService;
import com.alexandria.service.UserService;
import com.alexandria.storage.FileStorageService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class ServiceConfig {

    private final CategoryRepository categoryRepository;
    private final CommentRepository commentRepository;
    private final DocumentRepository documentRepository;
    private final InteractionRepository interactionRepository;
    private final ReadingListRepository readingListRepository;
    private final ReadingListItemRepository readingListItemRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final PasswordEncoder passwordEncoder;
    private final CategoryMapper categoryMapper;
    private final CommentMapper commentMapper;
    private final DocumentMapper documentMapper;
    private final ReadingListMapper readingListMapper;
    private final UserMapper userMapper;
    private final EntityManager entityManager;

    @Bean
    public CategoryService categoryService() {
        return new CategoryService(categoryRepository, categoryMapper);
    }

    @Bean
    public CommentService commentService() {
        return new CommentService(commentRepository, documentRepository, commentMapper);
    }

    @Bean
    public DocumentService documentService() {
        return new DocumentService(documentRepository, categoryRepository, userRepository, fileStorageService, documentMapper);
    }

    @Bean
    public InteractionService interactionService() {
        return new InteractionService(interactionRepository, documentRepository);
    }

    @Bean
    public ReadingListService readingListService() {
        return new ReadingListService(
                readingListRepository, readingListItemRepository, documentRepository,
                readingListMapper, interactionService());
    }

    @Bean
    public RecommendationQueryRunner recommendationQueryRunner() {
        return new JpaRecommendationQueryRunner(entityManager);
    }

    @Bean
    public RecommendationService recommendationService() {
        return new RecommendationService(
                interactionRepository, recommendationQueryRunner(), documentRepository, documentMapper);
    }

    @Bean
    public UserService userService() {
        return new UserService(userRepository, userMapper, passwordEncoder);
    }

    @Bean("ownership")
    public OwnershipService ownershipService() {
        return new OwnershipService(documentRepository, userRepository, readingListRepository, commentRepository);
    }

    @Bean
    public SecurityUtils securityUtils() {
        return new SecurityUtils(userRepository);
    }
}
