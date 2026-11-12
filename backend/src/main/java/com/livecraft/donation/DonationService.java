package com.livecraft.donation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.livecraft.auth.AuthUser;
import com.livecraft.broadcast.Broadcast;
import com.livecraft.broadcast.BroadcastService;
import com.livecraft.event.BroadcastEventHub;
import com.livecraft.global.AppProperties;
import com.livecraft.global.Tokens;
import com.livecraft.global.error.ApiException;
import com.livecraft.minecraft.GameAction;
import com.livecraft.minecraft.GameActionRepository;
import com.livecraft.minecraft.GameActionService;
import com.livecraft.minecraft.GameActionService.Target;
import com.livecraft.minecraft.McMemberRepository;
import com.livecraft.minecraft.DonationRuleRepository;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 후원 흐름
 * 1) prepare: 금액·대상을 서버에 PENDING 으로 저장 (게임 반응을 실행할 수 없으면 여기서 거절)
 * 2) 토스 결제창 (프론트)
 * 3) confirm: 저장된 금액과 대조 → 실행 가능 여부 재확인 → 토스 승인 → finalize
 * 4) 웹훅: 토스에 결제를 다시 조회해 검증 → finalize
 * finalize 는 잠금 안에서 PAID 여부를 보고 한 번만 적립·반응 등록한다 (중복 통지 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

    public static final long MIN_AMOUNT = 1_000;
    public static final long MAX_AMOUNT = 10_000_000;

    private final DonationRepository donationRepository;
    private final BroadcastService broadcastService;
    private final UserRepository userRepository;
    private final GameActionService gameActionService;
    private final GameActionRepository gameActionRepository;
    private final McMemberRepository memberRepository;
    private final DonationRuleRepository ruleRepository;
    private final TossPaymentsClient toss;
    private final BroadcastEventHub eventHub;
    private final TransactionTemplate tx;
    private final AppProperties props;

    // ---------- 응답 형식 ----------

    public record RuleOption(long minAmount, String label, String actionType, int cooldownSeconds, long waiting, long etaSeconds) {}

    public record DonationOptions(Long broadcastId, Long streamerId, String streamerNickname, boolean live,
                                  boolean minecraftLinked, String serverName, String mcName, boolean pluginConnected,
                                  boolean playerOnline, List<RuleOption> rules, String tossClientKey, long minAmount, long maxAmount) {}

    public record PrepareResult(String orderId, long amount, String orderName, String customerKey, String tossClientKey,
                                String ruleLabel) {}

    public record DonationDto(Long id, String donorNickname, long amount, String message, Instant paidAt,
                              String actionLabel, GameAction.Status actionStatus, String actionTarget) {}

    public record DonationSummary(long total, int count, List<DonationDto> items) {}

    // ---------- 1. 후원 화면 정보 ----------

    @Transactional(readOnly = true)
    public DonationOptions options(Long broadcastId) {
        Broadcast b = broadcastService.find(broadcastId);
        Long streamerId = b.getOwner().getId();
        var membership = memberRepository.findAccepted(streamerId);
        if (membership.isEmpty()) {
            return new DonationOptions(b.getId(), streamerId, b.getOwner().getNickname(), b.getStatus() == Broadcast.Status.LIVE,
                    false, null, null, false, false, List.of(), props.toss().clientKey(), MIN_AMOUNT, MAX_AMOUNT);
        }
        var m = membership.get();
        Long serverId = m.getServer().getId();
        var probe = new Target(m, null);
        List<RuleOption> rules = ruleRepository.findByServerIdOrderByMinAmountAsc(serverId).stream().map(r -> {
            var q = gameActionService.queueInfo(serverId, r, m.getMcName());
            return new RuleOption(r.getMinAmount(), r.getLabel(), r.getActionType().name(), r.getCooldownSeconds(), q.waiting(), q.etaSeconds());
        }).toList();
        return new DonationOptions(b.getId(), streamerId, b.getOwner().getNickname(), b.getStatus() == Broadcast.Status.LIVE,
                true, m.getServer().getName(), m.getMcName(), gameActionService.isPluginConnected(serverId),
                gameActionService.isDeliverable(probe), rules, props.toss().clientKey(), MIN_AMOUNT, MAX_AMOUNT);
    }

    // ---------- 2. 결제 준비 ----------

    @Transactional
    public PrepareResult prepare(AuthUser me, Long broadcastId, long amount, String message) {
        Broadcast b = broadcastService.find(broadcastId);
        if (b.getStatus() != Broadcast.Status.LIVE) throw ApiException.conflict("NOT_LIVE", "방송 중일 때만 후원할 수 있습니다.");
        if (b.isOwnedBy(me.id())) throw ApiException.badRequest("SELF_DONATION", "자기 방송에는 후원할 수 없습니다.");
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            throw ApiException.badRequest("INVALID_AMOUNT", "후원 금액은 1,000원 ~ 10,000,000원입니다.");
        }
        var target = gameActionService.resolveTarget(b.getOwner().getId(), amount);
        target.ifPresent(gameActionService::ensureDeliverable); // 실행할 수 없는 반응이면 결제창을 열지 않는다

        String orderId = "don_" + Tokens.random(18);
        donationRepository.save(new Donation(orderId, b, userRepository.getReferenceById(me.id()), b.getOwner(), amount, message));
        String orderName = b.getOwner().getNickname() + "님 후원";
        return new PrepareResult(orderId, amount, orderName, "user_" + me.id(), props.toss().clientKey(),
                target.map(t -> t.rule().getLabel()).orElse(null));
    }

    // ---------- 3. 결제 승인 (결제창 성공 후 프론트가 호출) ----------

    public DonationDto confirm(AuthUser me, String paymentKey, String orderId, long amount) {
        // 3-1. 저장된 주문과 대조 (잠금 안)
        // 실패 기록(markFailed)이 롤백되지 않게, 트랜잭션 안에서는 예외를 던지지 않고 결과만 돌려준다
        record Check(boolean alreadyPaid, ApiException error) {}
        Check check = tx.execute(s -> {
            Donation d = donationRepository.findForUpdate(orderId).orElse(null);
            if (d == null) return new Check(false, ApiException.notFound("후원 주문"));
            if (!d.getDonor().getId().equals(me.id())) return new Check(false, ApiException.forbidden("본인의 후원만 승인할 수 있습니다."));
            if (d.getStatus() == Donation.Status.PAID) return new Check(true, null);
            if (d.getStatus() == Donation.Status.FAILED) {
                return new Check(false, ApiException.conflict("DONATION_FAILED", "이미 실패한 후원입니다. 다시 시도해 주세요."));
            }
            if (d.getAmount() != amount) {
                d.markFailed("금액 불일치");
                return new Check(false, ApiException.badRequest("AMOUNT_MISMATCH", "결제 금액이 주문 금액과 다릅니다."));
            }
            // 결제창이 열려 있는 동안 대상이 나갔을 수 있다 → 승인 직전에 다시 확인 (승인 안 하면 돈이 나가지 않음)
            try {
                gameActionService.resolveTarget(d.getStreamer().getId(), d.getAmount()).ifPresent(gameActionService::ensureDeliverable);
            } catch (ApiException e) {
                d.markFailed(e.getMessage());
                return new Check(false, e);
            }
            return new Check(false, null);
        });
        if (check.error() != null) throw check.error();
        if (check.alreadyPaid()) return find(orderId);

        // 3-2. 토스 승인 (트랜잭션 밖: 외부 호출 동안 DB 잠금을 잡지 않는다)
        TossPaymentsClient.TossPayment payment;
        try {
            payment = toss.confirm(paymentKey, orderId, amount);
        } catch (TossPaymentsClient.TossException e) {
            // 이미 승인된 결제(웹훅이 먼저 처리 등)면 조회해서 확정, 아니면 실패 처리
            if ("ALREADY_PROCESSED_PAYMENT".equals(e.code())) {
                payment = toss.get(paymentKey);
            } else {
                tx.executeWithoutResult(s -> donationRepository.findForUpdate(orderId).ifPresent(d -> d.markFailed(e.getMessage())));
                throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_FAILED", e.getMessage());
            }
        }
        finalizePaid(payment);
        return find(orderId);
    }

    // ---------- 4. 토스 웹훅 (같은 통지가 여러 번 와도 안전) ----------

    public void onWebhook(String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) return;
        // 본문을 믿지 않고 토스에 직접 다시 조회한다 (위조 통지 방지)
        TossPaymentsClient.TossPayment payment = toss.get(paymentKey);
        if ("DONE".equals(payment.status())) finalizePaid(payment);
    }

    /** 승인 결과 검증 + 한 번만 적립. 반환값: 이번에 새로 확정했는지 */
    boolean finalizePaid(TossPaymentsClient.TossPayment p) {
        Boolean created = tx.execute(s -> {
            Donation d = donationRepository.findForUpdate(p.orderId()).orElse(null);
            if (d == null) {
                log.warn("알 수 없는 주문의 결제 통지 orderId={}", p.orderId());
                return false;
            }
            if (!"DONE".equals(p.status())) return false;
            if (d.getAmount() != p.totalAmount()) {
                log.error("결제 금액 불일치 orderId={} 주문={} 결제={}", p.orderId(), d.getAmount(), p.totalAmount());
                return false;
            }
            if (!d.markPaid(p.paymentKey())) return false; // 이미 적립된 결제 → 무시

            Broadcast b = d.getBroadcast();
            var target = gameActionService.resolveTarget(d.getStreamer().getId(), d.getAmount());
            GameAction action = target.map(t -> gameActionService.enqueue(t, d.getId(), b.getId(), d.getDonor().getNickname(), d.getAmount()))
                    .orElse(null);
            eventHub.publish(b.getId(), "DONATION", toDto(d, action));
            return true;
        });
        return Boolean.TRUE.equals(created);
    }

    // ---------- 조회 ----------

    @Transactional(readOnly = true)
    public DonationSummary list(Long broadcastId) {
        broadcastService.find(broadcastId);
        List<Donation> paid = donationRepository.findPaid(broadcastId);
        Map<Long, GameAction> actions = gameActionRepository.findByDonationIdIn(paid.stream().map(Donation::getId).toList())
                .stream().collect(Collectors.toMap(GameAction::getDonationId, Function.identity()));
        List<DonationDto> items = paid.stream().map(d -> toDto(d, actions.get(d.getId()))).toList();
        return new DonationSummary(donationRepository.sumPaid(broadcastId), items.size(), items);
    }

    private DonationDto find(String orderId) {
        return tx.execute(s -> {
            Donation d = donationRepository.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("후원"));
            var action = gameActionRepository.findByDonationIdIn(List.of(d.getId())).stream().findFirst().orElse(null);
            return toDto(d, action);
        });
    }

    private static DonationDto toDto(Donation d, GameAction a) {
        return new DonationDto(d.getId(), d.getDonor().getNickname(), d.getAmount(), d.getMessage(), d.getPaidAt(),
                a == null ? null : a.getLabel(), a == null ? null : a.getStatus(), a == null ? null : a.getTargetMcName());
    }
}
