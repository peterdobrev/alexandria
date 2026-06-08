package com.alexandria.service;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.CreateReadingListRequest;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.UpdateReadingListRequest;
import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.User;
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
import java.util.UUID;

@Transactional
@RequiredArgsConstructor
public class ReadingListService {

    private final ReadingListRepository readingListRepository;
    private final ReadingListItemRepository readingListItemRepository;
    private final DocumentRepository documentRepository;
    private final ReadingListMapper readingListMapper;

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
        return readingListMapper.toResponse(readingListRepository.save(list));
    }

    @Transactional(readOnly = true)
    public ReadingListResponse getReadingList(UUID id) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        return readingListMapper.toResponse(list);
    }

    public ReadingListResponse updateReadingList(UUID id, UpdateReadingListRequest request) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        list.setName(request.name());
        return readingListMapper.toResponse(readingListRepository.save(list));
    }

    public void deleteReadingList(UUID id) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        readingListRepository.delete(list);
    }

    public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request) {
        ReadingList list = readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        Document document = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        if (readingListItemRepository.findByReadingListIdAndDocumentId(listId, request.documentId()).isPresent()) {
            throw new ReadingListItemAlreadyExistsException(listId, request.documentId());
        }
        ReadingListItem item = new ReadingListItem();
        item.setReadingList(list);
        item.setDocument(document);
        item.setAddedAt(Instant.now());
        return readingListMapper.toItemResponse(readingListItemRepository.save(item));
    }

    public void removeItem(UUID listId, UUID documentId) {
        readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        ReadingListItem item = readingListItemRepository.findByReadingListIdAndDocumentId(listId, documentId)
                .orElseThrow(() -> new ReadingListItemNotFoundException(listId, documentId));
        readingListItemRepository.delete(item);
    }
}
