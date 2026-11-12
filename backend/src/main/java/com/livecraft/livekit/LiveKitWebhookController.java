package com.livecraft.livekit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.broadcast.BroadcastService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class LiveKitWebhookController {

    private final LiveKitService liveKitService;
    private final BroadcastService broadcastService;

    @PostMapping(value = "/api/livekit/webhook", consumes = {"application/webhook+json", "application/json"})
    public ResponseEntity<Void> webhook(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody String body) {
        JsonNode event = liveKitService.verifyWebhook(authorization, body);
        if (event == null) {
            return ResponseEntity.status(401).build();
        }
        broadcastService.onLiveKitEvent(
                event.path("event").asString(),
                event.path("room").path("name").asString(),
                event.path("participant").path("identity").asString());
        return ResponseEntity.ok().build();
    }
}
