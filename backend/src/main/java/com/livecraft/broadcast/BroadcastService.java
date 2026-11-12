package com.livecraft.broadcast;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.auth.AuthUser;
import com.livecraft.event.BroadcastEventHub;
import com.livecraft.global.AppProperties;
import com.livecraft.global.Tokens;
import com.livecraft.global.error.ApiException;
import com.livecraft.livekit.LiveKitService;
import com.livecraft.user.User;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    private final BroadcastRepository broadcastRepository;
    private final UserRepository userRepository;
    private final LiveKitService liveKitService;
    private final BroadcastEventHub eventHub;
    private final AppProperties props;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional
    public BroadcastDto create(AuthUser me, String title, String description) {
        User owner = userRepository.getReferenceById(me.id());
        Broadcast b = broadcastRepository.save(new Broadcast(owner, title, description, "bc-" + Tokens.random(12)));
        return BroadcastDto.from(broadcastRepository.findActive(b.getId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<BroadcastDto> list(Broadcast.Status status) {
        return broadcastRepository.findList(status).stream().map(BroadcastDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<BroadcastDto> mine(AuthUser me) {
        return broadcastRepository.findByOwner(me.id()).stream().map(BroadcastDto::from).toList();
    }

    @Transactional(readOnly = true)
    public BroadcastDto get(Long id) {
        return BroadcastDto.from(find(id));
    }

    @Transactional
    public BroadcastDto update(AuthUser me, Long id, String title, String description) {
        Broadcast b = findOwned(me, id);
        b.update(title, description);
        return BroadcastDto.from(b);
    }

    @Transactional
    public void delete(AuthUser me, Long id) {
        Broadcast b = findOwned(me, id);
        endInternal(b); // 방송 중이면 송출부터 끊는다
        b.delete();
    }

    /** 송출 시작(또는 새로고침 후 재접속): 소유자에게만 송출 권한 토큰을 준다 */
    @Transactional
    public LiveKitService.JoinInfo startPublishing(AuthUser me, Long id, Broadcast.SourceType sourceType) {
        Broadcast b = findOwned(me, id);
        boolean wasLive = b.getStatus() == Broadcast.Status.LIVE;
        b.goLive(sourceType);
        if (!wasLive) eventHub.publish(b.getId(), "BROADCAST_STARTED", BroadcastDto.from(b));
        return liveKitService.publisherToken(b.getRoomName(), b.publisherIdentity(), me.nickname());
    }

    /** 시청 토큰: 로그인 없이도 받을 수 있지만 송출 권한은 없다 */
    @Transactional(readOnly = true)
    public LiveKitService.JoinInfo viewerToken(Long id, AuthUser meOrNull) {
        Broadcast b = find(id);
        if (b.getStatus() != Broadcast.Status.LIVE) {
            throw ApiException.conflict("NOT_LIVE", "방송 중이 아닙니다.");
        }
        String identity = (meOrNull != null ? "user-" + meOrNull.id() + "-" : "guest-") + Tokens.random(6);
        String name = meOrNull != null ? meOrNull.nickname() : "손님";
        return liveKitService.viewerToken(b.getRoomName(), identity, name);
    }

    @Transactional
    public BroadcastDto end(AuthUser me, Long id) {
        Broadcast b = findOwned(me, id);
        endInternal(b);
        return BroadcastDto.from(b);
    }

    /** LiveKit 웹훅: 송출자가 나가면 유예 시간을 재고, 돌아오면 취소, 방이 사라지면 종료 */
    @Transactional
    public void onLiveKitEvent(String event, String room, String identity) {
        broadcastRepository.findByRoomName(room).ifPresent(b -> {
            boolean isPublisher = b.publisherIdentity().equals(identity);
            switch (event) {
                case "participant_joined" -> { if (isPublisher) b.publisherJoined(); }
                case "participant_left" -> { if (isPublisher) b.publisherLeft(); }
                case "room_finished" -> endInternal(b);
                default -> { }
            }
        });
    }

    /** 송출자가 유예 시간 넘게 돌아오지 않은 방송을 종료 (탭 닫힘·네트워크 끊김) */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void endAbandoned() {
        Instant limit = Instant.now().minusSeconds(props.livekit().publisherGraceSeconds());
        for (Broadcast b : broadcastRepository.findByStatusAndPublisherLeftAtBefore(Broadcast.Status.LIVE, limit)) {
            log.info("송출자 이탈로 방송 종료 id={}", b.getId());
            endInternal(b);
        }
    }

    private void endInternal(Broadcast b) {
        if (b.end()) {
            liveKitService.deleteRoom(b.getRoomName());
            eventHub.publish(b.getId(), "BROADCAST_ENDED", Map.of("id", b.getId()));
            events.publishEvent(new BroadcastEndedEvent(b.getId()));
        }
    }

    public Broadcast find(Long id) {
        return broadcastRepository.findActive(id).orElseThrow(() -> ApiException.notFound("방송"));
    }

    private Broadcast findOwned(AuthUser me, Long id) {
        Broadcast b = find(id);
        if (!b.isOwnedBy(me.id())) {
            throw ApiException.forbidden("방송 소유자만 할 수 있습니다.");
        }
        return b;
    }
}
