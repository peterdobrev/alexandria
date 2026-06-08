package com.alexandria.mapper;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.entity.Comment;
import org.springframework.stereotype.Component;

@Component
public class CommentMapper {

    private final UserMapper userMapper = new UserMapper();

    public CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                userMapper.toAuthorSummary(comment.getAuthor()),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }
}
