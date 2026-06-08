package com.alexandria.service;

import com.alexandria.entity.Document;
import com.alexandria.entity.ReadingList;
import com.alexandria.entity.User;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListRepository;
import com.alexandria.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("ownership")
@RequiredArgsConstructor
public class OwnershipService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final ReadingListRepository readingListRepository;

    public boolean isDocumentOwner(UUID documentId, UserDetails principal) {
        if (principal == null) {
            return false;
        }
        return documentRepository.findById(documentId)
                .map(Document::getAuthor)
                .map(User::getEmail)
                .map(email -> email.equals(principal.getUsername()))
                .orElse(false);
    }

    public boolean isSelf(UUID userId, UserDetails principal) {
        if (principal == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(User::getEmail)
                .map(email -> email.equals(principal.getUsername()))
                .orElse(false);
    }

    public boolean isReadingListOwner(UUID listId, UserDetails principal) {
        if (principal == null) {
            return false;
        }
        return readingListRepository.findById(listId)
                .map(ReadingList::getUser)
                .map(User::getEmail)
                .map(email -> email.equals(principal.getUsername()))
                .orElse(false);
    }
}
