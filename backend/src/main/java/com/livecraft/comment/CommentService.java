package com.livecraft.comment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.auth.AuthUser;
import com.livecraft.broadcast.Broadcast;
import com.livecraft.broadcast.BroadcastService;
import com.livecraft.event.BroadcastEventHub;
import com.livecraft.global.error.ApiException;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommentService {

    private static final int MAX_LIST = 200;

    private final CommentRepository commentRepository;
    private final BroadcastService broadcastService;
    private final UserRepository userRepository;
    private final BroadcastEventHub eventHub;

    public record CommentDto(Long id, Long authorId, String authorNickname, String content, boolean edited, Instant createdAt) {
        static CommentDto from(Comment c) {
            return new CommentDto(c.getId(), c.getAuthor().getId(), c.getAuthor().getNickname(), c.getContent(), c.isEdited(), c.getCreatedAt());
        }
    }

    /** 최근 댓글을 오래된 순으로 */
    @Transactional(readOnly = true)
    public List<CommentDto> list(Long broadcastId) {
        broadcastService.find(broadcastId);
        return commentRepository.findRecent(broadcastId, PageRequest.of(0, MAX_LIST)).reversed().stream()
                .map(CommentDto::from).toList();
    }

    @Transactional
    public CommentDto create(AuthUser me, Long broadcastId, String content) {
        Broadcast b = broadcastService.find(broadcastId);
        Comment c = commentRepository.save(new Comment(b, userRepository.getReferenceById(me.id()), content));
        CommentDto dto = new CommentDto(c.getId(), me.id(), me.nickname(), c.getContent(), false, c.getCreatedAt());
        eventHub.publish(broadcastId, "COMMENT_CREATED", dto);
        return dto;
    }

    @Transactional
    public CommentDto update(AuthUser me, Long broadcastId, Long commentId, String content) {
        Comment c = findWritten(me, broadcastId, commentId);
        c.edit(content);
        CommentDto dto = CommentDto.from(c);
        eventHub.publish(broadcastId, "COMMENT_UPDATED", dto);
        return dto;
    }

    @Transactional
    public void delete(AuthUser me, Long broadcastId, Long commentId) {
        Comment c = findWritten(me, broadcastId, commentId);
        commentRepository.delete(c);
        eventHub.publish(broadcastId, "COMMENT_DELETED", Map.of("id", commentId));
    }

    /** 작성자 본인 확인은 서버에서 한다 (화면에서 버튼을 숨기는 것만으로는 막을 수 없다) */
    private Comment findWritten(AuthUser me, Long broadcastId, Long commentId) {
        Comment c = commentRepository.findById(commentId)
                .filter(x -> x.getBroadcast().getId().equals(broadcastId))
                .orElseThrow(() -> ApiException.notFound("댓글"));
        if (!c.isWrittenBy(me.id())) {
            throw ApiException.forbidden("작성자만 수정·삭제할 수 있습니다.");
        }
        return c;
    }
}
