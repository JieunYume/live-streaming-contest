package com.livecraft.minecraft;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.auth.AuthUser;
import com.livecraft.minecraft.McServerService.ActionDto;
import com.livecraft.minecraft.McServerService.InviteDto;
import com.livecraft.minecraft.McServerService.LinkResult;
import com.livecraft.minecraft.McServerService.RuleInput;
import com.livecraft.minecraft.McServerService.ServerDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class McServerController {

    private final McServerService service;

    public record NameRequest(@NotBlank @Size(max = 50) String name) {}

    public record InviteRequest(@NotBlank String mcName, @NotBlank String username) {}

    public record LinkRequest(@NotBlank String code) {}

    @GetMapping("/api/mc/servers")
    public List<ServerDto> myServers(@AuthenticationPrincipal AuthUser me) {
        return service.myServers(me);
    }

    @PostMapping("/api/mc/servers")
    @ResponseStatus(HttpStatus.CREATED)
    public ServerDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody NameRequest req) {
        return service.create(me, req.name());
    }

    @PutMapping("/api/mc/servers/{id}")
    public ServerDto rename(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody NameRequest req) {
        return service.rename(me, id, req.name());
    }

    @DeleteMapping("/api/mc/servers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        service.delete(me, id);
    }

    @PostMapping("/api/mc/servers/{id}/link-code")
    public ServerDto newLinkCode(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.newLinkCode(me, id);
    }

    @PostMapping("/api/mc/servers/{id}/members")
    public ServerDto invite(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @Valid @RequestBody InviteRequest req) {
        return service.invite(me, id, req.mcName().trim(), req.username().trim());
    }

    @DeleteMapping("/api/mc/servers/{id}/members/{memberId}")
    public ServerDto removeMember(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable Long memberId) {
        return service.removeMember(me, id, memberId);
    }

    @PostMapping("/api/mc/servers/{id}/rules")
    public ServerDto addRule(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @RequestBody RuleInput req) {
        return service.addRule(me, id, req);
    }

    @PutMapping("/api/mc/servers/{id}/rules/{ruleId}")
    public ServerDto updateRule(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable Long ruleId, @RequestBody RuleInput req) {
        return service.updateRule(me, id, ruleId, req);
    }

    @DeleteMapping("/api/mc/servers/{id}/rules/{ruleId}")
    public ServerDto deleteRule(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable Long ruleId) {
        return service.deleteRule(me, id, ruleId);
    }

    @GetMapping("/api/mc/servers/{id}/actions")
    public List<ActionDto> actions(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.actions(me, id);
    }

    @PostMapping("/api/mc/servers/{id}/actions/{actionId}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@AuthenticationPrincipal AuthUser me, @PathVariable Long id, @PathVariable Long actionId) {
        service.retryAction(me, id, actionId);
    }

    @GetMapping("/api/mc/invites")
    public List<InviteDto> myInvites(@AuthenticationPrincipal AuthUser me) {
        return service.myInvites(me);
    }

    @PostMapping("/api/mc/invites/{memberId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@AuthenticationPrincipal AuthUser me, @PathVariable Long memberId) {
        service.acceptInvite(me, memberId);
    }

    @DeleteMapping("/api/mc/invites/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthUser me, @PathVariable Long memberId) {
        service.leave(me, memberId);
    }

    /** 플러그인 전용: 게임에서 입력한 연동 코드 → 플러그인 토큰 */
    @PostMapping("/api/plugin/link")
    public LinkResult link(@Valid @RequestBody LinkRequest req) {
        return service.link(req.code());
    }
}
