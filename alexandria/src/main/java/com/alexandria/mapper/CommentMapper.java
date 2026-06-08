package com.alexandria.mapper;

import com.alexandria.dto.comment.CommentResponse;
import com.alexandria.entity.Comment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommentMapper {

    private final UserMapper userMapper;

    public CommentResponse toResponse(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                userMapper.toAuthorSummary(comment.getAuthor()),
                comment.getBody(),
                comment.getCreatedAt()
        );
    }
}
