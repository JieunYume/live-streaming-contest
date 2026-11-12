package com.livecraft.donation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.livecraft.broadcast.Broadcast;
import com.livecraft.broadcast.BroadcastRepository;
import com.livecraft.global.Tokens;
import com.livecraft.minecraft.DonationRule;
import com.livecraft.minecraft.DonationRuleRepository;
import com.livecraft.minecraft.GameAction;
import com.livecraft.minecraft.GameActionRepository;
import com.livecraft.minecraft.GameActionService;
import com.livecraft.minecraft.McMember;
import com.livecraft.minecraft.McMemberRepository;
import com.livecraft.minecraft.McServer;
import com.livecraft.minecraft.McServerRepository;
import com.livecraft.minecraft.plugin.PluginSessionRegistry;
import com.livecraft.user.User;
import com.livecraft.user.UserRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * - 같은 승인 통지가 여러 번 와도 한 번만 적립 + 게임 반응 1개
 * - 금액이 주문과 다르면 확정하지 않음
 * - 쿨타임: 같은 (규칙, 대상)은 간격을 지키고, 다른 대상은 막지 않음
 */
@SpringBootTest
class DonationAndGameActionTest {

    @Autowired DonationService donationService;
    @Autowired GameActionService gameActionService;
    @Autowired DonationRepository donationRepository;
    @Autowired GameActionRepository actionRepository;
    @Autowired BroadcastRepository broadcastRepository;
    @Autowired UserRepository userRepository;
    @Autowired McServerRepository serverRepository;
    @Autowired McMemberRepository memberRepository;
    @Autowired DonationRuleRepository ruleRepository;
    @Autowired PluginSessionRegistry registry;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    record Fixture(Broadcast broadcast, User donor, McServer server, DonationRule rule) {}

    private Fixture setup(String mcName, int cooldown) {
        return tx.execute(s -> {
            String suffix = Tokens.random(4);
            User streamer = userRepository.save(new User("st_" + suffix, "x", "스트리머"));
            User donor = userRepository.save(new User("dn_" + suffix, "x", "후원자"));
            Broadcast b = new Broadcast(streamer, "방송", null, "bc-" + suffix);
            b.goLive(Broadcast.SourceType.SCREEN);
            broadcastRepository.save(b);
            McServer server = serverRepository.save(new McServer(streamer, "서버"));
            McMember m = new McMember(server, streamer, mcName);
            m.accept();
            memberRepository.save(m);
            DonationRule rule = ruleRepository.save(new DonationRule(server, 30_000, "랜덤박스", DonationRule.ActionType.RANDOM_BOX, null, cooldown));
            return new Fixture(b, donor, server, rule);
        });
    }

    private List<String> send(Long serverId) {
        return tx.execute(s -> gameActionService.collectToSend(serverId));
    }

    private String newOrder(Fixture f, long amount) {
        String orderId = "don_" + Tokens.random(8);
        tx.executeWithoutResult(s -> donationRepository.save(
                new Donation(orderId, f.broadcast(), f.donor(), f.broadcast().getOwner(), amount, "응원해요")));
        return orderId;
    }

    @Test
    void 중복_승인_통지는_한_번만_적립() {
        Fixture f = setup("Steve", 0);
        String orderId = newOrder(f, 30_000);
        var payment = new TossPaymentsClient.TossPayment("pk_" + orderId, orderId, "DONE", 30_000);

        assertThat(donationService.finalizePaid(payment)).isTrue();
        assertThat(donationService.finalizePaid(payment)).isFalse(); // 같은 통지 재전송
        assertThat(donationService.finalizePaid(payment)).isFalse();

        var summary = donationService.list(f.broadcast().getId());
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(30_000);
        assertThat(summary.items().getFirst().actionLabel()).isEqualTo("랜덤박스");
        assertThat(actionRepository.findByDonationIdIn(List.of(summary.items().getFirst().id()))).hasSize(1);
    }

    @Test
    void 금액이_다르면_확정하지_않음() {
        Fixture f = setup("Alex", 0);
        String orderId = newOrder(f, 30_000);
        assertThat(donationService.finalizePaid(new TossPaymentsClient.TossPayment("pk_" + orderId, orderId, "DONE", 1_000))).isFalse();
        assertThat(donationService.list(f.broadcast().getId()).count()).isZero();
    }

    @Test
    void 쿨타임은_같은_규칙과_대상끼리만() {
        Fixture f = setup("Herobrine", 60);
        Long serverId = f.server().getId();
        registry.setPlayers(serverId, List.of("Herobrine"));

        var p1 = new TossPaymentsClient.TossPayment("pk1_" + serverId, newOrder(f, 30_000), "DONE", 30_000);
        var p2 = new TossPaymentsClient.TossPayment("pk2_" + serverId, newOrder(f, 50_000), "DONE", 50_000);
        donationService.finalizePaid(p1);
        donationService.finalizePaid(p2);

        // 첫 실행: 묶음 맨 앞 1건만 전송
        List<String> first = send(serverId);
        assertThat(first).hasSize(1);
        Long firstId = json.readTree(first.getFirst()).get("actionId").asLong();

        // 응답 전에는 다음 건을 보내지 않음
        assertThat(send(serverId)).isEmpty();

        // 완료 → 쿨타임 60초라 두 번째는 아직 대기
        gameActionService.ack(serverId, firstId, true, "ok");
        gameActionService.ack(serverId, firstId, true, "ok"); // 중복 ACK 무시
        assertThat(send(serverId)).isEmpty();
        assertThat(actionRepository.findById(firstId).orElseThrow().getStatus()).isEqualTo(GameAction.Status.DONE);

        // 대상이 오프라인이면 후원 준비 단계에서 거절할 수 있도록 실행 불가 판정
        registry.setPlayers(serverId, List.of());
        var target = gameActionService.resolveTarget(f.broadcast().getOwner().getId(), 30_000).orElseThrow();
        assertThat(gameActionService.isDeliverable(target)).isFalse();
    }
}
