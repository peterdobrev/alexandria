package com.alexandria.service;

import com.alexandria.dto.AddReadingListItemRequest;
import com.alexandria.dto.CreateReadingListRequest;
import com.alexandria.dto.ReadingListItemResponse;
import com.alexandria.dto.ReadingListResponse;
import com.alexandria.dto.ReadingListSummaryResponse;
import com.alexandria.dto.UpdateReadingListRequest;
import com.alexandria.dto.document.AuthorSummary;
import com.alexandria.dto.document.CategorySummary;
import com.alexandria.dto.document.DocumentSummary;
import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.ReadingListItem;
import com.alexandria.entity.User;
import com.alexandria.entity.Visibility;
import com.alexandria.exception.DocumentNotFoundException;
import com.alexandria.exception.ReadingListItemNotFoundException;
import com.alexandria.exception.ReadingListNotFoundException;
import com.alexandria.mapper.ReadingListMapper;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListItemRepository;
import com.alexandria.repository.ReadingListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingListServiceTest {

    @Mock
    private ReadingListRepository readingListRepository;
    @Mock
    private ReadingListItemRepository readingListItemRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ReadingListMapper readingListMapper;

    private ReadingListService classUnderTest;

    @BeforeEach
    void setUp() {
        classUnderTest = new ReadingListService(
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

        Page<ReadingListSummaryResponse> result = classUnderTest.getReadingLists(currentUser, Pageable.unpaged());

        assertThat(result.getContent()).containsExactly(summary);
    }

    @Test
    void createReadingList_validRequest_savesWithNameAndOwner_andReturnsResponse() {
        User currentUser = userWithId();
        CreateReadingListRequest request = new CreateReadingListRequest("My List");
        ReadingList saved = new ReadingList();
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.save(any(ReadingList.class))).thenReturn(saved);
        when(readingListMapper.toResponse(saved)).thenReturn(response);

        ReadingListResponse result = classUnderTest.createReadingList(request, currentUser);

        assertThat(result).isEqualTo(response);

        ArgumentCaptor<ReadingList> captor = ArgumentCaptor.forClass(ReadingList.class);
        verify(readingListRepository).save(captor.capture());
        ReadingList toSave = captor.getValue();
        assertThat(toSave.getName()).isEqualTo("My List");
        assertThat(toSave.getUser()).isSameAs(currentUser);
        assertThat(toSave.getCreatedAt()).isNotNull();
        assertThat(toSave.getItems()).isEmpty();
    }

    @Test
    void getReadingList_existingId_returnsResponse() {
        UUID listId = UUID.randomUUID();
        ReadingList list = new ReadingList();
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListMapper.toResponse(list)).thenReturn(response);

        assertThat(classUnderTest.getReadingList(listId)).isEqualTo(response);
    }

    @Test
    void getReadingList_unknownId_throwsReadingListNotFoundException() {
        UUID listId = UUID.randomUUID();
        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.getReadingList(listId))
                .isInstanceOf(ReadingListNotFoundException.class);
    }

    @Test
    void updateReadingList_existingId_updatesNameAndReturnsResponse() {
        UUID listId = UUID.randomUUID();
        ReadingList list = new ReadingList();
        list.setName("Old Name");
        UpdateReadingListRequest request = new UpdateReadingListRequest("Updated Name");
        ReadingListResponse response = readingListResponse();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListRepository.save(list)).thenReturn(list);
        when(readingListMapper.toResponse(list)).thenReturn(response);

        assertThat(classUnderTest.updateReadingList(listId, request)).isEqualTo(response);
        assertThat(list.getName()).isEqualTo("Updated Name");
    }

    @Test
    void updateReadingList_unknownId_throwsReadingListNotFoundException() {
        UUID listId = UUID.randomUUID();
        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.updateReadingList(listId, new UpdateReadingListRequest("Name")))
                .isInstanceOf(ReadingListNotFoundException.class);

        verify(readingListRepository, never()).save(any());
    }

    @Test
    void deleteReadingList_existingId_deletesReadingList() {
        UUID listId = UUID.randomUUID();
        ReadingList list = new ReadingList();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));

        classUnderTest.deleteReadingList(listId);

        verify(readingListRepository).delete(list);
    }

    @Test
    void deleteReadingList_unknownId_throwsReadingListNotFoundException() {
        UUID listId = UUID.randomUUID();
        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.deleteReadingList(listId))
                .isInstanceOf(ReadingListNotFoundException.class);

        verify(readingListRepository, never()).delete(any(ReadingList.class));
    }

    @Test
    void addItem_validRequest_savesAndReturnsItemResponse() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = new ReadingList();
        Document document = new Document();
        ReadingListItem savedItem = new ReadingListItem();
        ReadingListItemResponse itemResponse = itemResponse(docId);

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(readingListItemRepository.save(any(ReadingListItem.class))).thenReturn(savedItem);
        when(readingListMapper.toItemResponse(savedItem)).thenReturn(itemResponse);

        ReadingListItemResponse result = classUnderTest.addItem(listId, new AddReadingListItemRequest(docId));

        assertThat(result).isEqualTo(itemResponse);

        ArgumentCaptor<ReadingListItem> captor = ArgumentCaptor.forClass(ReadingListItem.class);
        verify(readingListItemRepository).save(captor.capture());
        ReadingListItem toSave = captor.getValue();
        assertThat(toSave.getReadingList()).isSameAs(list);
        assertThat(toSave.getDocument()).isSameAs(document);
        assertThat(toSave.getAddedAt()).isNotNull();
    }

    @Test
    void addItem_unknownList_throwsReadingListNotFoundException() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.addItem(listId, new AddReadingListItemRequest(docId)))
                .isInstanceOf(ReadingListNotFoundException.class);

        verify(readingListItemRepository, never()).save(any());
    }

    @Test
    void addItem_unknownDocument_throwsDocumentNotFoundException() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = new ReadingList();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.addItem(listId, new AddReadingListItemRequest(docId)))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(readingListItemRepository, never()).save(any());
    }

    @Test
    void removeItem_existingItem_deletesItem() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = new ReadingList();
        ReadingListItem item = new ReadingListItem();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListItemRepository.findByReadingListIdAndDocumentId(listId, docId))
                .thenReturn(Optional.of(item));

        classUnderTest.removeItem(listId, docId);

        verify(readingListItemRepository).delete(item);
    }

    @Test
    void removeItem_unknownList_throwsReadingListNotFoundException() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        when(readingListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.removeItem(listId, docId))
                .isInstanceOf(ReadingListNotFoundException.class);

        verify(readingListItemRepository, never()).delete(any(ReadingListItem.class));
    }

    @Test
    void removeItem_itemNotInList_throwsReadingListItemNotFoundException() {
        UUID listId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ReadingList list = new ReadingList();

        when(readingListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(readingListItemRepository.findByReadingListIdAndDocumentId(listId, docId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> classUnderTest.removeItem(listId, docId))
                .isInstanceOf(ReadingListItemNotFoundException.class);

        verify(readingListItemRepository, never()).delete(any(ReadingListItem.class));
    }

    private static User userWithId() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static ReadingListResponse readingListResponse() {
        return new ReadingListResponse(UUID.randomUUID(), "My List", Instant.now(), List.of());
    }

    private static ReadingListItemResponse itemResponse(UUID docId) {
        DocumentSummary documentSummary = new DocumentSummary(
                docId,
                "Title",
                "Description",
                "PDF",
                Visibility.PUBLIC,
                new AuthorSummary(UUID.randomUUID(), "Author"),
                Set.<CategorySummary>of(),
                false,
                false,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        return new ReadingListItemResponse(UUID.randomUUID(), documentSummary, Instant.now());
    }
}
