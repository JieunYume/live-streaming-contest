package com.livecraft.donation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.livecraft.global.AppProperties;
import com.livecraft.global.error.ApiException;

/** 토스페이먼츠 결제 API (테스트 키면 실제 돈이 나가지 않는다). DB 트랜잭션 밖에서 호출한다 */
@Component
public class TossPaymentsClient {

    private final RestClient restClient;
    private final boolean configured;

    public record TossPayment(String paymentKey, String orderId, String status, long totalAmount) {}

    public record TossError(String code, String message) {}

    public static class TossException extends RuntimeException {
        private final String code;

        TossException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public TossPaymentsClient(AppProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        String secret = props.toss().secretKey() == null ? "" : props.toss().secretKey();
        this.configured = !secret.isBlank();
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.toss().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString((secret + ":").getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    public TossPayment confirm(String paymentKey, String orderId, long amount) {
        return call(() -> restClient.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(TossPayment.class));
    }

    public TossPayment get(String paymentKey) {
        return call(() -> restClient.get().uri("/v1/payments/{paymentKey}", paymentKey).retrieve().body(TossPayment.class));
    }

    private TossPayment call(Supplier<TossPayment> request) {
        if (!configured) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED", "결제 키(TOSS_SECRET_KEY)가 설정되지 않았습니다.");
        }
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            TossError err = null;
            try {
                err = e.getResponseBodyAs(TossError.class);
            } catch (RuntimeException ignored) {
                // 토스 형식이 아닌 응답
            }
            throw new TossException(err != null ? err.code() : "UNKNOWN", err != null ? err.message() : "결제 승인에 실패했습니다.");
        } catch (ResourceAccessException e) {
            throw new TossException("TIMEOUT", "결제사 응답이 없습니다. 잠시 후 후원 내역을 확인해 주세요.");
        }
    }
}
