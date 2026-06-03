package com.alexandria.controller;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.CreateReadingListRequest;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.UpdateReadingListRequest;
import com.alexandria.security.SecurityUtils;
import com.alexandria.service.ReadingListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reading-lists")
@RequiredArgsConstructor
public class ReadingListController {

    private final ReadingListService readingListService;
    private final SecurityUtils securityUtils;

    @GetMapping
    public ResponseEntity<Page<ReadingListSummaryResponse>> getReadingLists(Pageable pageable) {
        return ResponseEntity.ok(readingListService.getReadingLists(securityUtils.getCurrentUser(), pageable));
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public ReadingListResponse createReadingList(@Valid @RequestBody CreateReadingListRequest request) {
        return readingListService.createReadingList(request, securityUtils.getCurrentUser());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReadingListResponse> getReadingList(@PathVariable UUID id) {
        return ResponseEntity.ok(readingListService.getReadingList(id, securityUtils.getCurrentUser()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReadingListResponse> updateReadingList(@PathVariable UUID id,
                                                                  @Valid @RequestBody UpdateReadingListRequest request) {
        return ResponseEntity.ok(readingListService.updateReadingList(id, request, securityUtils.getCurrentUser()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReadingList(@PathVariable UUID id) {
        readingListService.deleteReadingList(id, securityUtils.getCurrentUser());
        return ResponseEntity.noContent().build();
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{id}/items")
    public ReadingListItemResponse addItem(@PathVariable UUID id,
                                           @Valid @RequestBody AddReadingListItemRequest request) {
        return readingListService.addItem(id, request, securityUtils.getCurrentUser());
    }

    @DeleteMapping("/{id}/items/{docId}")
    public ResponseEntity<Void> removeItem(@PathVariable UUID id, @PathVariable UUID docId) {
        readingListService.removeItem(id, docId, securityUtils.getCurrentUser());
        return ResponseEntity.noContent().build();
    }
}
