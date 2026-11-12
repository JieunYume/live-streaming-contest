package com.livecraft.recording;

import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.auth.AuthUser;
import com.livecraft.recording.RecordingService.RecordingDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class RecordingController {

    private final RecordingService recordingService;

    public record StartRequest(String mimeType) {}

    @PostMapping("/api/broadcasts/{broadcastId}/recordings")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordingDto start(@AuthenticationPrincipal AuthUser me, @PathVariable Long broadcastId, @RequestBody(required = false) StartRequest req) {
        return recordingService.start(me, broadcastId, req == null ? null : req.mimeType());
    }

    @PostMapping(value = "/api/recordings/{id}/chunks", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void chunk(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestParam int seq, @RequestBody byte[] data) {
        recordingService.appendChunk(me, id, seq, data);
    }

    @PostMapping("/api/recordings/{id}/finish")
    public RecordingDto finish(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return recordingService.finish(me, id);
    }

    @GetMapping("/api/recordings")
    public List<RecordingDto> list() {
        return recordingService.list();
    }

    @GetMapping("/api/recordings/{id}")
    public RecordingDto get(@PathVariable Long id) {
        return recordingService.get(id);
    }

    /** Range 요청을 Spring 이 알아서 처리해 영상 탐색(건너뛰기)이 된다 */
    @GetMapping("/api/recordings/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable Long id) {
        RecordingService.VideoFile file = recordingService.video(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .body(new FileSystemResource(file.path()));
    }
}
