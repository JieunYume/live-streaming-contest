package com.livecraft.comment;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.auth.AuthUser;
import com.livecraft.comment.CommentService.CommentDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/broadcasts/{broadcastId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    public record CommentRequest(@NotBlank @Size(max = 300) String content) {}

    @GetMapping
    public List<CommentDto> list(@PathVariable Long broadcastId) {
        return commentService.list(broadcastId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto create(@AuthenticationPrincipal AuthUser me, @PathVariable Long broadcastId, @Valid @RequestBody CommentRequest req) {
        return commentService.create(me, broadcastId, req.content());
    }

    @PutMapping("/{commentId}")
    public CommentDto update(@AuthenticationPrincipal AuthUser me, @PathVariable Long broadcastId, @PathVariable Long commentId,
                             @Valid @RequestBody CommentRequest req) {
        return commentService.update(me, broadcastId, commentId, req.content());
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long broadcastId, @PathVariable Long commentId) {
        commentService.delete(me, broadcastId, commentId);
    }
}
