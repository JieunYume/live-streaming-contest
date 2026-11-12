package com.livecraft.event;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * 방송별 실시간 이벤트(SSE): 댓글 작성/수정/삭제, 후원 알림, 게임 반응 결과, 방송 종료.
 * shortcut: 구독자 목록을 메모리에 둔다. 서버를 여러 대로 늘리면 Redis pub/sub 으로 바꾼다.
 */
@Slf4j
@Component
public class BroadcastEventHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long broadcastId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(broadcastId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("CONNECTED").data("ok"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    public void publish(Long broadcastId, String type, Object data) {
        List<SseEmitter> list = emitters.get(broadcastId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    /** 프록시가 유휴 연결을 끊지 않도록 주기적으로 빈 신호를 보낸다 */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach((id, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }
}
