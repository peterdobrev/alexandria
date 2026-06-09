package com.alexandria.controller;

import com.alexandria.dto.common.PageResponse;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.recommendation.CreateInteractionRequest;
import com.alexandria.entity.InteractionKind;
import com.alexandria.entity.User;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.InteractionService;
import com.alexandria.service.RecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendationController {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_PAGE_NUMBER = 200;

    private final RecommendationService recommendationService;
    private final InteractionService interactionService;
    private final SecurityUtils securityUtils;

    @GetMapping("/recommendations")
    public ResponseEntity<PageResponse<DocumentSummary>> getRecommendations(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Recommendations page size exceeds the maximum of " + MAX_PAGE_SIZE);
        }
        if (pageable.getPageNumber() > MAX_PAGE_NUMBER) {
            throw new IllegalArgumentException(
                    "Recommendations page number exceeds the maximum of " + MAX_PAGE_NUMBER);
        }
        User currentUser = securityUtils.getCurrentUser();
        return ResponseEntity.ok(recommendationService.getRecommendations(currentUser.getId(), pageable));
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/documents/{id}/interactions")
    public void logInteraction(@PathVariable("id") UUID documentId,
                               @Valid @RequestBody CreateInteractionRequest request) {
        if (request.kind() != InteractionKind.VIEW) {
            throw new IllegalArgumentException("Only VIEW interactions can be posted by clients");
        }
        User currentUser = securityUtils.getCurrentUser();
        interactionService.logView(currentUser, documentId);
    }
}
