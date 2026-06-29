package com.alexandria.service;

import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.dto.readinglist.AddReadingListItemRequest;
import com.alexandria.dto.readinglist.CreateReadingListRequest;
import com.alexandria.dto.readinglist.ReadingListItemResponse;
import com.alexandria.dto.readinglist.ReadingListResponse;
import com.alexandria.dto.readinglist.ReadingListSummaryResponse;
import com.alexandria.dto.readinglist.UpdateReadingListRequest;
import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ReadingListItemAlreadyExistsException;
import com.alexandria.exception.ReadingListItemNotFoundException;
import com.alexandria.exception.ReadingListNotFoundException;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListItemRepository;
import com.alexandria.repository.ReadingListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Transactional
@RequiredArgsConstructor
public class ReadingListService {

    private final ReadingListRepository readingListRepository;
    private final ReadingListItemRepository readingListItemRepository;
    private final DocumentRepository documentRepository;
    private final ReadingListMapper readingListMapper;
    private final InteractionService interactionService;

    @Transactional(readOnly = true)
    public Page<ReadingListSummaryResponse> getReadingLists(User currentUser, Pageable pageable) {
        return readingListRepository.findByUserId(currentUser.getId(), pageable)
                .map(readingListMapper::toSummaryResponse);
    }

    public ReadingListResponse createReadingList(CreateReadingListRequest request, User currentUser) {
        ReadingList list = new ReadingList();
        list.setName(request.name());
        list.setUser(currentUser);
        list.setCreatedAt(Instant.now());
        list.setItems(new ArrayList<>());
        ReadingList saved = readingListRepository.save(list);
        return readingListMapper.toResponse(saved, documentSummaries(saved));
    }

    @Transactional(readOnly = true)
    public ReadingListResponse getReadingList(UUID id) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        return readingListMapper.toResponse(list, documentSummaries(list));
    }

    public ReadingListResponse updateReadingList(UUID id, UpdateReadingListRequest request) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        list.setName(request.name());
        ReadingList saved = readingListRepository.save(list);
        return readingListMapper.toResponse(saved, documentSummaries(saved));
    }

    private Map<UUID, DocumentSummary> documentSummaries(ReadingList list) {
        List<ReadingListItem> items = list.getItems();
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        List<UUID> documentIds = items.stream()
                .map(item -> item.getDocument().getId())
                .toList();
        return documentRepository.findSummariesByIds(documentIds).stream()
                .collect(Collectors.toMap(DocumentSummary::id, Function.identity()));
    }

    public void deleteReadingList(UUID id) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        readingListRepository.delete(list);
    }

    public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request, UUID currentUserId) {
        ReadingList list = readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        Document document = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        if (document.getVisibility() == Visibility.PRIVATE
                && !document.getAuthor().getId().equals(currentUserId)) {
            throw new DocumentNotFoundException(request.documentId());
        }
        if (readingListItemRepository.findByReadingListIdAndDocumentId(listId, request.documentId()).isPresent()) {
            throw new ReadingListItemAlreadyExistsException(listId, request.documentId());
        }
        ReadingListItem item = new ReadingListItem();
        item.setReadingList(list);
        item.setDocument(document);
        item.setAddedAt(Instant.now());
        ReadingListItemResponse response = readingListMapper.toItemResponse(readingListItemRepository.save(item));
        interactionService.logBookmark(list.getUser(), document);
        return response;
    }

    public void removeItem(UUID listId, UUID documentId) {
        readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        ReadingListItem item = readingListItemRepository.findByReadingListIdAndDocumentId(listId, documentId)
                .orElseThrow(() -> new ReadingListItemNotFoundException(listId, documentId));
        readingListItemRepository.delete(item);
    }
}
