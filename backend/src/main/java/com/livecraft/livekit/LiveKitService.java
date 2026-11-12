package com.livecraft.livekit;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.livecraft.global.AppProperties;
import com.livecraft.global.Tokens;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LiveKit 연동
 * - 접속 토큰: LiveKit 규격의 JWT(HS256). 송출 가능 여부(canPublish)를 여기서 정하므로 송출 권한의 최종 관문이다.
 * - 방 닫기: RoomService/DeleteRoom 호출 → 송출·시청 연결이 서버에서 강제로 끊긴다.
 * - 웹훅 검증: Authorization 헤더의 JWT 서명과 본문 SHA-256 을 확인한다.
 * 공식 SDK 대신 직접 서명하는 이유: 의존성 하나 없이 20줄이면 되고, 개발용 짧은 키에도 동작한다.
 */
@Slf4j
@Service
public class LiveKitService {

    private static final Duration TOKEN_TTL = Duration.ofHours(6);

    private final AppProperties.LiveKit config;
    private final JsonMapper jsonMapper;
    private final RestClient restClient;

    public LiveKitService(AppProperties props, JsonMapper jsonMapper) {
        this.config = props.livekit();
        this.jsonMapper = jsonMapper;
        this.restClient = RestClient.builder().baseUrl(config.apiUrl()).build();
    }

    public record JoinInfo(String url, String token, String room, String identity) {}

    public JoinInfo publisherToken(String room, String identity, String name) {
        return new JoinInfo(config.url(), accessToken(room, identity, name, true), room, identity);
    }

    public JoinInfo viewerToken(String room, String identity, String name) {
        return new JoinInfo(config.url(), accessToken(room, identity, name, false), room, identity);
    }

    String accessToken(String room, String identity, String name, boolean canPublish) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", canPublish);
        video.put("canSubscribe", true);
        video.put("canPublishData", false);
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", config.apiKey());
        claims.put("sub", identity);
        claims.put("name", name);
        claims.put("nbf", now.getEpochSecond() - 10);
        claims.put("exp", now.plus(TOKEN_TTL).getEpochSecond());
        claims.put("video", video);
        return sign(claims);
    }

    /** 방을 닫아 모든 연결을 끊는다. LiveKit 이 꺼져 있어도 방송 종료 자체는 진행되도록 실패는 로그만 남긴다 */
    public void deleteRoom(String room) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", config.apiKey());
        claims.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
        claims.put("video", Map.of("roomCreate", true, "room", room));
        try {
            restClient.post()
                    .uri("/twirp/livekit.RoomService/DeleteRoom")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("room", room))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("LiveKit 방 닫기 실패 room={}: {}", room, e.getMessage());
        }
    }

    /** 웹훅 서명 확인 후 본문을 돌려준다. 위조면 null */
    public JsonNode verifyWebhook(String authorization, String body) {
        if (authorization == null) return null;
        String jwt = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        String expected = hmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        JsonNode claims = jsonMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        if (!config.apiKey().equals(claims.path("iss").asString())) return null;
        if (claims.has("exp") && claims.get("exp").asLong() < Instant.now().getEpochSecond()) return null;
        String bodyHash = Base64.getEncoder().encodeToString(Tokens.sha256(body.getBytes(StandardCharsets.UTF_8)));
        if (!bodyHash.equals(claims.path("sha256").asString())) return null;
        return jsonMapper.readTree(body);
    }

    private String sign(Map<String, Object> claims) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(jsonMapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        return signingInput + "." + hmac(signingInput);
    }

    private String hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
