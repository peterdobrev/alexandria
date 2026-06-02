package com.alexandria.service;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.CreateReadingListRequest;
import com.alexandria.dto.DocumentSummaryResponse;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.UpdateReadingListRequest;
import com.alexandria.dto.UserResponse;
import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.User;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ForbiddenException;
import com.alexandria.exception.ReadingListNotFoundException;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListItemRepository;
import com.alexandria.repository.ReadingListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingListServiceTest {

    @Mock private ReadingListRepository readingListRepository;
    @Mock private ReadingListItemRepository readingListItemRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private ReadingListMapper readingListMapper;

    private ReadingListService readingListService;

    @BeforeEach
    void setUp() {
        readingListService = new ReadingListService(
                readingListRepository, readingListItemRepository, documentRepository, readingListMapper);
    }

    @Test
    void getReadingLists_returnsPageOfSummaries() {
        User currentUser = userWithId();
        ReadingList list = new ReadingList();
        ReadingListSummaryResponse summary = new ReadingListSummaryResponse(UUID.randomUUID(), "My List", Instant.now());
        Page<ReadingList> page = new PageImpl<>(List.of(list));

        when(readingListRepository.findByUserId(currentUser.getId(), Pageable.unpaged())).thenReturn(page);
        when(readingListMapper.toSummaryResponse(list)).thenReturn(summary);

        Page<ReadingListSummaryResponse> result = readingListService.getReadingLists(currentUser, Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(summary);
    }

    @Test
    void createReadingList_validRequest_returnsReadingListResponse() {
        User currentUser = userWithId();
        CreateReadingListRequest request = new CreateReadingListRequest("My List");
        ReadingList saved = new ReadingList();
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.save(any(ReadingList.class))).thenReturn(saved);
        when(readingListMapper.toResponse(saved)).thenReturn(response);

        assertThat(readingListService.createReadingList(request, currentUser)).isEqualTo(response);
        verify(readingListRepository).save(any(ReadingList.class));
    }

    @Test
    void getReadingList_owner_returnsReadingListResponse() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListMapper.toResponse(list)).thenReturn(response);

        assertThat(readingListService.getReadingList(listId, currentUser)).isEqualTo(response);
    }

    @Test
    void getReadingList_notOwner_throwsForbiddenException() {
        User currentUser = userWithId();
        User otherUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(otherUser);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> readingListService.getReadingList(listId, currentUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateReadingList_owner_updatesAndReturnsResponse() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);
        UpdateReadingListRequest request = new UpdateReadingListRequest("Updated Name");
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListRepository.save(list)).thenReturn(list);
        when(readingListMapper.toResponse(list)).thenReturn(response);

        assertThat(readingListService.updateReadingList(listId, request, currentUser)).isEqualTo(response);
        assertThat(list.getName()).isEqualTo("Updated Name");
    }

    @Test
    void updateReadingList_notOwner_throwsForbiddenException() {
        User currentUser = userWithId();
        User otherUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(otherUser);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> readingListService.updateReadingList(listId, new UpdateReadingListRequest("Name"), currentUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deleteReadingList_owner_deletesReadingList() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));

        readingListService.deleteReadingList(listId, currentUser);

        verify(readingListRepository).delete(list);
    }

    @Test
    void deleteReadingList_notOwner_throwsForbiddenException() {
        User currentUser = userWithId();
        User otherUser = userWithId();
        UUID listId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(otherUser);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> readingListService.deleteReadingList(listId, currentUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addItem_owner_savesAndReturnsItemResponse() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);
        Document document = new Document();
        ReadingListItem savedItem = new ReadingListItem();
        ReadingListItemResponse itemResponse = new ReadingListItemResponse(UUID.randomUUID(),
                new DocumentSummaryResponse(docId, "Title", "PDF",
                        new UserResponse(UUID.randomUUID(), "a@test.com", "Author", Instant.now()),
                        List.of(), Instant.now()),
                Instant.now());

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(readingListItemRepository.save(any(ReadingListItem.class))).thenReturn(savedItem);
        when(readingListMapper.toItemResponse(savedItem)).thenReturn(itemResponse);

        assertThat(readingListService.addItem(listId, new AddReadingListItemRequest(docId), currentUser))
                .isEqualTo(itemResponse);
        verify(readingListItemRepository).save(any(ReadingListItem.class));
    }

    @Test
    void addItem_documentNotFound_throwsDocumentNotFoundException() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> readingListService.addItem(listId, new AddReadingListItemRequest(docId), currentUser))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void removeItem_owner_deletesItem() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = readingListOwnedBy(currentUser);
        ReadingListItem item = new ReadingListItem();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListItemRepository.findByReadingListIdAndDocumentId(listId, docId)).thenReturn(Optional.of(item));

        readingListService.removeItem(listId, docId, currentUser);

        verify(readingListItemRepository).delete(item);
    }

    @Test
    void removeItem_listNotFound_throwsReadingListNotFoundException() {
        User currentUser = userWithId();
        UUID listId = UUID.randomUUID();

        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> readingListService.removeItem(listId, UUID.randomUUID(), currentUser))
                .isInstanceOf(ReadingListNotFoundException.class);
    }

    private User userWithId() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private ReadingList readingListOwnedBy(User owner) {
        ReadingList list = new ReadingList();
        list.setUser(owner);
        list.setName("Test List");
        list.setItems(new ArrayList<>());
        return list;
    }

    private ReadingListResponse readingListResponse() {
        return new ReadingListResponse(UUID.randomUUID(), "My List", Instant.now(), List.of());
    }
}
