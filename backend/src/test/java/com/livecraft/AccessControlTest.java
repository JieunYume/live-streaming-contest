package com.livecraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공모전 요구사항 "비로그인 · 방송 소유자 · 다른 사용자" 별 허용/거절 확인
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void 방송_권한_흐름() throws Exception {
        String owner = login("streamer1");
        String other = login("streamer2");

        // 비로그인: 목록 조회 OK, 등록 401
        mvc.perform(get("/api/broadcasts")).andExpect(status().isOk());
        mvc.perform(post("/api/broadcasts").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        // 소유자: 등록
        JsonNode created = body(mvc.perform(auth(post("/api/broadcasts"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"마크 서버 방송\",\"description\":\"설명\"}")).andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        assertThat(created.get("status").asString()).isEqualTo("READY");

        // 비로그인 상세 조회 OK, 방송 전이라 시청 토큰은 409
        mvc.perform(get("/api/broadcasts/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/broadcasts/" + id + "/viewer-token")).andExpect(status().isConflict());

        // 다른 사용자: 수정·삭제·송출·종료 모두 403
        String update = "{\"title\":\"남의 방송 수정\"}";
        mvc.perform(auth(put("/api/broadcasts/" + id), other).contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/broadcasts/" + id), other)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/broadcasts/" + id + "/publish-token"), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"SCREEN\"}")).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/broadcasts/" + id + "/end"), other)).andExpect(status().isForbidden());

        // 비로그인 송출: 401
        mvc.perform(post("/api/broadcasts/" + id + "/publish-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"SCREEN\"}")).andExpect(status().isUnauthorized());

        // 소유자: 수정 → 송출 시작 (송출 권한 토큰)
        mvc.perform(auth(put("/api/broadcasts/" + id), owner).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정된 제목\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정된 제목"));
        JsonNode pub = body(mvc.perform(auth(post("/api/broadcasts/" + id + "/publish-token"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"CAMERA\"}")).andExpect(status().isOk()));
        assertThat(videoGrant(pub.get("token").asString()).get("canPublish").asBoolean()).isTrue();
        mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.status").value("LIVE"));

        // 비로그인 시청: 토큰은 받지만 송출 권한 없음
        JsonNode view = body(mvc.perform(get("/api/broadcasts/" + id + "/viewer-token")).andExpect(status().isOk()));
        assertThat(videoGrant(view.get("token").asString()).get("canPublish").asBoolean()).isFalse();

        // 소유자: 종료 → 시청 토큰 409, 다시 송출 불가
        mvc.perform(auth(post("/api/broadcasts/" + id + "/end"), owner)).andExpect(jsonPath("$.status").value("ENDED"));
        mvc.perform(get("/api/broadcasts/" + id + "/viewer-token")).andExpect(status().isConflict());
        mvc.perform(auth(post("/api/broadcasts/" + id + "/publish-token"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"SCREEN\"}")).andExpect(status().isConflict());

        // 소유자: 삭제 → 404
        mvc.perform(auth(delete("/api/broadcasts/" + id), owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/broadcasts/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 댓글은_작성자만_수정_삭제() throws Exception {
        String owner = login("streamer1");
        String viewer = login("viewer1");
        String other = login("streamer2");
        long id = body(mvc.perform(auth(post("/api/broadcasts"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"댓글 테스트\"}"))).get("id").asLong();

        mvc.perform(post("/api/broadcasts/" + id + "/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        long commentId = body(mvc.perform(auth(post("/api/broadcasts/" + id + "/comments"), viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"안녕하세요\"}")).andExpect(status().isCreated())).get("id").asLong();

        mvc.perform(get("/api/broadcasts/" + id + "/comments")).andExpect(jsonPath("$[0].content").value("안녕하세요"));
        mvc.perform(auth(put("/api/broadcasts/" + id + "/comments/" + commentId), other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"해킹\"}")).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/broadcasts/" + id + "/comments/" + commentId), owner)).andExpect(status().isForbidden());
        mvc.perform(auth(put("/api/broadcasts/" + id + "/comments/" + commentId), viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정\"}")).andExpect(jsonPath("$.edited").value(true));
        mvc.perform(auth(delete("/api/broadcasts/" + id + "/comments/" + commentId), viewer)).andExpect(status().isNoContent());
    }

    @Test
    void 로그아웃하면_토큰이_무효() throws Exception {
        String token = login("viewer1");
        mvc.perform(auth(get("/api/auth/me"), token)).andExpect(status().isOk());
        mvc.perform(auth(post("/api/auth/logout"), token)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/auth/me"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void 회원가입과_로그인() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser1\",\"password\":\"password123\",\"nickname\":\"새유저\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.nickname").value("새유저"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser1\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser1\",\"password\":\"password123\",\"nickname\":\"중복\"}"))
                .andExpect(status().isConflict());
    }

    private String login(String username) throws Exception {
        return body(mvc.perform(post("/api/auth/test-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\"}")).andExpect(status().isOk())).get("token").asString();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode videoGrant(String jwt) {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1])).get("video");
    }
}
