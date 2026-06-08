package com.alexandria.mapper;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.entity.Comment;
import com.alexandria.entity.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMapperTest {

    private final CommentMapper mapper = new CommentMapper(new UserMapper());

    @Test
    void toResponse_commentWithAllFieldsSet_mapsAllFieldsToResponse() {
        User author = new User();
        author.setId(UUID.randomUUID());
        author.setDisplayName("Alice");

        Comment comment = new Comment();
        comment.setId(UUID.randomUUID());
        comment.setAuthor(author);
        comment.setBody("Great doc!");
        comment.setCreatedAt(Instant.now());

        CommentResponse response = mapper.toResponse(comment);

        assertThat(response.id()).isEqualTo(comment.getId());
        assertThat(response.author().id()).isEqualTo(author.getId());
        assertThat(response.author().displayName()).isEqualTo("Alice");
        assertThat(response.body()).isEqualTo("Great doc!");
        assertThat(response.createdAt()).isEqualTo(comment.getCreatedAt());
    }
}
