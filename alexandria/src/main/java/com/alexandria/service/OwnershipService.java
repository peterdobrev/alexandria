package com.alexandria.service;

import com.alexandria.entity.User;
import com.alexandria.repository.CommentRepository;
import com.alexandria.repository.DocumentRepository;
import com.alexandria.repository.ReadingListRepository;
import com.alexandria.repository.UserRepository;
import com.alexandria.security.RoleNames;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.UUID;

@RequiredArgsConstructor
public class OwnershipService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final ReadingListRepository readingListRepository;
    private final CommentRepository commentRepository;

    public boolean isDocumentOwner(UUID documentId, UserDetails principal) {
        if (principal == null) {
            return false;
        }
        // A missing entity denies rather than throwing: authorization must not reveal
        // whether a (possibly private) resource exists. The service layer issues the
        // correct 404 for callers who pass authorization.
        return documentRepository.findById(documentId)
                .map(document -> document.getAuthor().getEmail().equals(principal.getUsername()))
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
                .map(list -> list.getUser().getEmail().equals(principal.getUsername()))
                .orElse(false);
    }

    public boolean isCommentOwnerOrAdmin(UUID commentId, UserDetails principal) {
        if (principal == null) {
            return false;
        }
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> RoleNames.ADMIN.equals(a.getAuthority()));
        if (isAdmin) {
            // Admins may moderate any comment, including on private documents — intentional design
            return true;
        }
        return commentRepository.findById(commentId)
                .map(comment -> comment.getAuthor().getEmail().equals(principal.getUsername()))
                .orElse(false);
    }
}
