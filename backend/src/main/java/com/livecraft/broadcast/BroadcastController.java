package com.livecraft.broadcast;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.livecraft.auth.AuthUser;
import com.livecraft.event.BroadcastEventHub;
import com.livecraft.livekit.LiveKitService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final BroadcastEventHub eventHub;

    public record BroadcastRequest(@NotBlank @Size(max = 100) String title, @Size(max = 1000) String description) {}

    public record PublishRequest(@NotNull Broadcast.SourceType sourceType) {}

    @GetMapping
    public List<BroadcastDto> list(@RequestParam(required = false) Broadcast.Status status) {
        return broadcastService.list(status);
    }

    @GetMapping("/mine")
    public List<BroadcastDto> mine(@AuthenticationPrincipal AuthUser me) {
        return broadcastService.mine(me);
    }

    @GetMapping("/{id}")
    public BroadcastDto get(@PathVariable Long id) {
        return broadcastService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BroadcastDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody BroadcastRequest req) {
        return broadcastService.create(me, req.title(), req.description());
    }

    @PutMapping("/{id}")
    public BroadcastDto update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody BroadcastRequest req) {
        return broadcastService.update(me, id, req.title(), req.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        broadcastService.delete(me, id);
    }

    @PostMapping("/{id}/publish-token")
    public LiveKitService.JoinInfo publishToken(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                                @Valid @RequestBody PublishRequest req) {
        return broadcastService.startPublishing(me, id, req.sourceType());
    }

    @GetMapping("/{id}/viewer-token")
    public LiveKitService.JoinInfo viewerToken(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return broadcastService.viewerToken(id, me);
    }

    @PostMapping("/{id}/end")
    public BroadcastDto end(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return broadcastService.end(me, id);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id) {
        broadcastService.find(id); // 없는 방송이면 404
        return eventHub.subscribe(id);
    }
}
