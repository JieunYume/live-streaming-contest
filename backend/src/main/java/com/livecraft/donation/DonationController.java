package com.livecraft.donation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.auth.AuthUser;
import com.livecraft.donation.DonationService.DonationDto;
import com.livecraft.donation.DonationService.DonationOptions;
import com.livecraft.donation.DonationService.DonationSummary;
import com.livecraft.donation.DonationService.PrepareResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DonationController {

    private final DonationService donationService;

    public record PrepareRequest(@NotNull Long broadcastId, long amount, @Size(max = 100) String message) {}

    public record ConfirmRequest(@NotBlank String paymentKey, @NotBlank String orderId, long amount) {}

    @GetMapping("/api/broadcasts/{broadcastId}/donation-options")
    public DonationOptions options(@PathVariable Long broadcastId) {
        return donationService.options(broadcastId);
    }

    @GetMapping("/api/broadcasts/{broadcastId}/donations")
    public DonationSummary list(@PathVariable Long broadcastId) {
        return donationService.list(broadcastId);
    }

    @PostMapping("/api/donations/prepare")
    public PrepareResult prepare(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody PrepareRequest req) {
        return donationService.prepare(me, req.broadcastId(), req.amount(), req.message());
    }

    @PostMapping("/api/donations/confirm")
    public DonationDto confirm(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ConfirmRequest req) {
        return donationService.confirm(me, req.paymentKey(), req.orderId(), req.amount());
    }

    /**
     * 토스 웹훅 (PAYMENT_STATUS_CHANGED). 본문은 paymentKey 를 얻는 데만 쓰고, 결과는 토스에 다시 조회해 확인한다.
     * 처리에 실패해도 200 을 돌려주면 토스가 재전송하지 않으므로, 실패 시 500 으로 재전송을 유도한다.
     */
    @PostMapping("/api/payments/toss/webhook")
    public ResponseEntity<Void> webhook(@RequestBody JsonNode body) {
        String paymentKey = body.path("data").path("paymentKey").asString(null);
        try {
            donationService.onWebhook(paymentKey);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.warn("토스 웹훅 처리 실패 paymentKey={}: {}", paymentKey, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
