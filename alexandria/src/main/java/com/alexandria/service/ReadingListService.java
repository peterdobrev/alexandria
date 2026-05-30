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
import com.alexandria.exception.ForbiddenException;
import com.alexandria.exception.ReadingListNotFoundException;
import com.alexandria.exception.ResourceNotFoundException;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListItemRepository;
import com.alexandria.repository.ReadingListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

@Service
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
    public ReadingListResponse getReadingList(UUID id, User currentUser) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        if (!list.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to access this reading list");
        }
        return readingListMapper.toResponse(list);
    }

    public ReadingListResponse updateReadingList(UUID id, UpdateReadingListRequest request, User currentUser) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        if (!list.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to update this reading list");
        }
        list.setName(request.name());
        return readingListMapper.toResponse(readingListRepository.save(list));
    }

    public void deleteReadingList(UUID id, User currentUser) {
        ReadingList list = readingListRepository.findById(id)
                .orElseThrow(() -> new ReadingListNotFoundException(id));
        if (!list.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to delete this reading list");
        }
        readingListRepository.delete(list);
    }

    public ReadingListItemResponse addItem(UUID listId, AddReadingListItemRequest request, User currentUser) {
        ReadingList list = readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        if (!list.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to modify this reading list");
        }
        Document document = documentRepository.findById(request.documentId())
                .orElseThrow(() -> new DocumentNotFoundException(request.documentId()));
        ReadingListItem item = new ReadingListItem();
        item.setReadingList(list);
        item.setDocument(document);
        item.setAddedAt(Instant.now());
        return readingListMapper.toItemResponse(readingListItemRepository.save(item));
    }

    public void removeItem(UUID listId, UUID docId, User currentUser) {
        ReadingList list = readingListRepository.findById(listId)
                .orElseThrow(() -> new ReadingListNotFoundException(listId));
        if (!list.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You don't have permission to modify this reading list");
        }
        ReadingListItem item = readingListItemRepository.findByReadingListIdAndDocumentId(listId, docId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found in reading list"));
        readingListItemRepository.delete(item);
    }
}
