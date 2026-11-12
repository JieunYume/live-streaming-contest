package com.livecraft.minecraft;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.event.BroadcastEventHub;
import com.livecraft.global.AppProperties;
import com.livecraft.global.error.ApiException;
import com.livecraft.minecraft.plugin.PluginSessionRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 후원 → 게임 반응 대기열.
 * 전달 보장: 최소 1번 전달(재전송) + 중복 제거(서버: donationId UNIQUE / 플러그인: 실행한 actionId 기록) = 정확히 1번 실행.
 * 쿨타임: 같은 (규칙, 대상) 묶음 안에서만 순서와 간격을 지키고, 다른 묶음은 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameActionService {

    private static final List<GameAction.Status> WAITING = List.of(GameAction.Status.PENDING, GameAction.Status.SENT);

    private final GameActionRepository actionRepository;
    private final McMemberRepository memberRepository;
    private final DonationRuleRepository ruleRepository;
    private final PluginSessionRegistry registry;
    private final BroadcastEventHub eventHub;
    private final JsonMapper jsonMapper;
    private final AppProperties props;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    /** 후원 대상 스트리머가 연동된 서버·규칙 (없으면 일반 후원) */
    public record Target(McMember member, DonationRule rule) {
        public Long serverId() { return member.getServer().getId(); }
    }

    public record QueueInfo(long waiting, long etaSeconds) {}

    @Transactional(readOnly = true)
    public Optional<Target> resolveTarget(Long streamerUserId, long amount) {
        return memberRepository.findAccepted(streamerUserId).flatMap(m ->
                ruleRepository.findFirstByServerIdAndMinAmountLessThanEqualOrderByMinAmountDesc(m.getServer().getId(), amount)
                        .map(rule -> new Target(m, rule)));
    }

    /** 실행할 수 없는 후원은 받지 않는다: 플러그인 연결 + 대상 접속 중이어야 한다 */
    public void ensureDeliverable(Target t) {
        if (!registry.isConnected(t.serverId())) {
            throw ApiException.conflict("PLUGIN_OFFLINE", "마인크래프트 서버가 연결되어 있지 않아 지금은 후원할 수 없습니다.");
        }
        if (!registry.isOnline(t.serverId(), t.member().getMcName())) {
            throw ApiException.conflict("PLAYER_OFFLINE", t.member().getMcName() + " 님이 게임에 접속해 있지 않아 지금은 후원할 수 없습니다.");
        }
    }

    public boolean isPluginConnected(Long serverId) {
        return registry.isConnected(serverId);
    }

    public boolean isDeliverable(Target t) {
        return registry.isConnected(t.serverId()) && registry.isOnline(t.serverId(), t.member().getMcName());
    }

    /** 결제 전에 보여 줄 "대기 N건, 약 M초 후 실행" */
    @Transactional(readOnly = true)
    public QueueInfo queueInfo(Long serverId, DonationRule rule, String mcName) {
        String key = McMember.key(mcName);
        long waiting = actionRepository.countWaiting(serverId, rule.getId(), key);
        long sinceLast = actionRepository.lastDoneAt(serverId, rule.getId(), key)
                .map(t -> Instant.now().getEpochSecond() - t.getEpochSecond()).orElse(Long.MAX_VALUE);
        long firstWait = Math.max(0, rule.getCooldownSeconds() - Math.min(sinceLast, Integer.MAX_VALUE));
        long eta = waiting == 0 ? firstWait : firstWait + waiting * (long) rule.getCooldownSeconds();
        return new QueueInfo(waiting, eta);
    }

    /** 결제 확정과 같은 트랜잭션에서 호출된다 */
    @Transactional
    public GameAction enqueue(Target t, Long donationId, Long broadcastId, String donorNickname, long amount) {
        return actionRepository.save(new GameAction(t.member().getServer(), donationId, broadcastId, t.rule(),
                t.member().getMcName(), donorNickname, amount));
    }

    /** 1초마다: 연결된 서버별로 보낼 수 있는 반응을 보낸다 */
    @Scheduled(fixedDelay = 1_000)
    public void dispatchAll() {
        for (Long serverId : registry.connectedServerIds()) {
            try {
                // SENT 로 먼저 커밋한 뒤 전송한다. 전송 후 커밋하면 빠른 ACK(DONE)를 SENT 가 덮어쓸 수 있다.
                List<String> messages = tx.execute(s -> collectToSend(serverId));
                if (messages != null) messages.forEach(m -> registry.send(serverId, m)); // 실패하면 ACK 시간 초과 후 재전송
            } catch (RuntimeException e) {
                log.error("게임 반응 전송 중 오류 server={}", serverId, e);
            }
        }
    }

    /** 트랜잭션 안에서 호출: 보낼 반응을 SENT 로 표시하고 메시지를 돌려준다 */
    public List<String> collectToSend(Long serverId) {
        List<String> out = new java.util.ArrayList<>();
        Instant now = Instant.now();
        Set<String> seenGroups = new HashSet<>();
        for (GameAction a : actionRepository.findByServerIdAndStatusInOrderByIdAsc(serverId, WAITING)) {
            if (!seenGroups.add(a.groupKey())) continue; // 묶음마다 맨 앞 1건만 처리 (순서 보장)
            if (a.getStatus() == GameAction.Status.SENT) {
                if (a.getSentAt().plusSeconds(props.plugin().ackTimeoutSeconds()).isAfter(now)) continue; // 응답 대기 중
                if (a.getAttempts() >= props.plugin().maxAttempts()) {
                    a.giveUp("플러그인 응답 없음 (" + a.getAttempts() + "회 시도)");
                    publishResult(a);
                    continue;
                }
            } else {
                Optional<Instant> lastDone = actionRepository.lastDoneAt(serverId, a.getRuleId(), McMember.key(a.getTargetMcName()));
                if (lastDone.isPresent() && lastDone.get().plusSeconds(a.getCooldownSeconds()).isAfter(now)) continue; // 쿨타임
            }
            if (!registry.isOnline(serverId, a.getTargetMcName())) continue; // 재접속하면 이어서 실행
            a.markSent();
            out.add(toMessage(a));
        }
        return out;
    }

    @Transactional
    public void ack(Long serverId, Long actionId, boolean success, String message) {
        actionRepository.findById(actionId)
                .filter(a -> a.getServer().getId().equals(serverId)) // 다른 서버의 반응은 건드릴 수 없다
                .ifPresent(a -> {
                    if (a.complete(success, message)) publishResult(a);
                });
    }

    @Transactional
    public void retry(Long serverId, Long actionId) {
        GameAction a = actionRepository.findById(actionId)
                .filter(x -> x.getServer().getId().equals(serverId))
                .orElseThrow(() -> ApiException.notFound("게임 반응"));
        if (a.getStatus() != GameAction.Status.FAILED) {
            throw ApiException.conflict("NOT_FAILED", "실패한 반응만 다시 실행할 수 있습니다.");
        }
        a.retry();
    }

    private void publishResult(GameAction a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("donationId", a.getDonationId());
        data.put("actionId", a.getId());
        data.put("status", a.getStatus());
        data.put("label", a.getLabel());
        data.put("target", a.getTargetMcName());
        data.put("message", a.getResultMessage());
        eventHub.publish(a.getBroadcastId(), "GAME_ACTION", data);
    }

    private String toMessage(GameAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ACTION");
        m.put("actionId", a.getId());
        m.put("player", a.getTargetMcName());
        m.put("actionType", a.getActionType());
        m.put("param", a.getParam());
        m.put("label", a.getLabel());
        m.put("donor", a.getDonorNickname());
        m.put("amount", a.getAmount());
        return jsonMapper.writeValueAsString(m);
    }
}
