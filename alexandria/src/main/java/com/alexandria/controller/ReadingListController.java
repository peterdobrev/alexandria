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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
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

    @PostMapping
    public ResponseEntity<ReadingListResponse> createReadingList(@Valid @RequestBody CreateReadingListRequest request) {
        ReadingListResponse response = readingListService.createReadingList(request, securityUtils.getCurrentUser());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
    @GetMapping("/{id}")
    public ResponseEntity<ReadingListResponse> getReadingList(@PathVariable UUID id) {
        return ResponseEntity.ok(readingListService.getReadingList(id));
    }

    @PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
    @PutMapping("/{id}")
    public ResponseEntity<ReadingListResponse> updateReadingList(@PathVariable UUID id,
                                                                  @Valid @RequestBody UpdateReadingListRequest request) {
        return ResponseEntity.ok(readingListService.updateReadingList(id, request));
    }

    @PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReadingList(@PathVariable UUID id) {
        readingListService.deleteReadingList(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{id}/items")
    public ReadingListItemResponse addItem(@PathVariable UUID id,
                                           @Valid @RequestBody AddReadingListItemRequest request) {
        return readingListService.addItem(id, request);
    }

    @PreAuthorize("@ownership.isReadingListOwner(#id, principal)")
    @DeleteMapping("/{id}/items/{documentId}")
    public ResponseEntity<Void> removeItem(@PathVariable UUID id, @PathVariable UUID documentId) {
        readingListService.removeItem(id, documentId);
        return ResponseEntity.noContent().build();
    }
}
