package com.livecraft.minecraft;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.auth.AuthUser;
import com.livecraft.global.error.ApiException;
import com.livecraft.minecraft.plugin.PluginSessionRegistry;
import com.livecraft.user.User;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;

/** 서버장 기능: 서버 등록·연동 코드·화이트리스트 초대·후원 규칙·반응 기록. 모든 작업에서 서버장 본인인지 확인한다 */
@Service
@RequiredArgsConstructor
public class McServerService {

    private static final Pattern MC_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final McServerRepository serverRepository;
    private final McMemberRepository memberRepository;
    private final DonationRuleRepository ruleRepository;
    private final GameActionRepository actionRepository;
    private final UserRepository userRepository;
    private final PluginSessionRegistry registry;
    private final GameActionService gameActionService;

    // ---------- 응답 형식 ----------

    public record MemberDto(Long id, String mcName, Long userId, String username, String nickname, McMember.Status status, boolean online) {}

    public record RuleDto(Long id, long minAmount, String label, DonationRule.ActionType actionType, String param, int cooldownSeconds) {
        static RuleDto from(DonationRule r) {
            return new RuleDto(r.getId(), r.getMinAmount(), r.getLabel(), r.getActionType(), r.getParam(), r.getCooldownSeconds());
        }
    }

    public record ServerDto(Long id, String name, boolean linked, boolean connected, String linkCode, Instant linkCodeExpiresAt,
                            List<String> onlinePlayers, List<MemberDto> members, List<RuleDto> rules) {}

    public record ActionDto(Long id, Long donationId, String target, String label, DonationRule.ActionType actionType,
                            String donorNickname, long amount, GameAction.Status status, int attempts, String resultMessage,
                            Instant createdAt, Instant doneAt) {
        static ActionDto from(GameAction a) {
            return new ActionDto(a.getId(), a.getDonationId(), a.getTargetMcName(), a.getLabel(), a.getActionType(),
                    a.getDonorNickname(), a.getAmount(), a.getStatus(), a.getAttempts(), a.getResultMessage(), a.getCreatedAt(), a.getDoneAt());
        }
    }

    public record InviteDto(Long memberId, Long serverId, String serverName, String ownerNickname, String mcName, McMember.Status status) {}

    public record LinkResult(String token, Long serverId, String serverName) {}

    // ---------- 서버 ----------

    @Transactional(readOnly = true)
    public List<ServerDto> myServers(AuthUser me) {
        return serverRepository.findByOwner(me.id()).stream().map(this::toDto).toList();
    }

    @Transactional
    public ServerDto create(AuthUser me, String name) {
        McServer s = serverRepository.save(new McServer(userRepository.getReferenceById(me.id()), name));
        return toDto(s);
    }

    @Transactional
    public ServerDto rename(AuthUser me, Long serverId, String name) {
        McServer s = owned(me, serverId);
        s.rename(name);
        return toDto(s);
    }

    @Transactional
    public void delete(AuthUser me, Long serverId) {
        McServer s = owned(me, serverId);
        registry.disconnect(serverId);
        actionRepository.deleteByServerId(serverId);
        ruleRepository.deleteByServerId(serverId);
        memberRepository.deleteByServerId(serverId);
        serverRepository.delete(s);
    }

    @Transactional
    public ServerDto newLinkCode(AuthUser me, Long serverId) {
        McServer s = owned(me, serverId);
        s.newLinkCode();
        return toDto(s);
    }

    /** 플러그인이 게임 안에서 입력한 연동 코드로 영구 토큰을 받는다 (기존 연결은 끊김) */
    @Transactional
    public LinkResult link(String code) {
        McServer s = serverRepository.findByLinkCode(code.trim().toUpperCase())
                .filter(x -> x.isLinkCodeValid(Instant.now()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LINK_CODE", "연동 코드가 없거나 만료됐습니다. 대시보드에서 새 코드를 받아 주세요."));
        String token = s.link();
        registry.disconnect(s.getId());
        return new LinkResult(token, s.getId(), s.getName());
    }

    // ---------- 화이트리스트·초대 ----------

    @Transactional
    public ServerDto invite(AuthUser me, Long serverId, String mcName, String username) {
        McServer s = owned(me, serverId);
        if (!MC_NAME.matcher(mcName).matches()) {
            throw ApiException.badRequest("INVALID_MC_NAME", "마인크래프트 닉네임은 영문·숫자·_ 3~16자입니다.");
        }
        if (memberRepository.existsByServerIdAndMcNameKey(serverId, McMember.key(mcName))) {
            throw ApiException.conflict("DUPLICATE_MC_NAME", "이미 화이트리스트에 있는 닉네임입니다.");
        }
        User user = userRepository.findByUsername(username).orElseThrow(() -> ApiException.notFound("초대할 사용자"));
        memberRepository.save(new McMember(s, user, mcName));
        return toDto(s);
    }

    @Transactional
    public ServerDto removeMember(AuthUser me, Long serverId, Long memberId) {
        McServer s = owned(me, serverId);
        McMember m = memberRepository.findById(memberId).filter(x -> x.getServer().getId().equals(serverId))
                .orElseThrow(() -> ApiException.notFound("멤버"));
        memberRepository.delete(m);
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public List<InviteDto> myInvites(AuthUser me) {
        return memberRepository.findByUser(me.id()).stream()
                .map(m -> new InviteDto(m.getId(), m.getServer().getId(), m.getServer().getName(),
                        m.getServer().getOwner().getNickname(), m.getMcName(), m.getStatus()))
                .toList();
    }

    /** 후원이 한 서버로만 가도록, 한 사람은 서버 하나에만 참여할 수 있다 */
    @Transactional
    public void acceptInvite(AuthUser me, Long memberId) {
        McMember m = myMembership(me, memberId);
        if (m.getStatus() == McMember.Status.ACCEPTED) return;
        if (memberRepository.findAccepted(me.id()).isPresent()) {
            throw ApiException.conflict("ALREADY_JOINED", "이미 다른 서버에 참여 중입니다. 먼저 나가 주세요.");
        }
        m.accept();
    }

    @Transactional
    public void leave(AuthUser me, Long memberId) {
        memberRepository.delete(myMembership(me, memberId));
    }

    // ---------- 후원 규칙 ----------

    @Transactional
    public ServerDto addRule(AuthUser me, Long serverId, RuleInput in) {
        McServer s = owned(me, serverId);
        validate(in);
        if (ruleRepository.existsByServerIdAndMinAmount(serverId, in.minAmount())) {
            throw ApiException.conflict("DUPLICATE_RULE", "같은 금액의 규칙이 이미 있습니다.");
        }
        ruleRepository.save(new DonationRule(s, in.minAmount(), in.label(), in.actionType(), blankToNull(in.param()), in.cooldownSeconds()));
        return toDto(s);
    }

    @Transactional
    public ServerDto updateRule(AuthUser me, Long serverId, Long ruleId, RuleInput in) {
        McServer s = owned(me, serverId);
        validate(in);
        DonationRule r = ruleRepository.findById(ruleId).filter(x -> x.getServer().getId().equals(serverId))
                .orElseThrow(() -> ApiException.notFound("규칙"));
        if (r.getMinAmount() != in.minAmount() && ruleRepository.existsByServerIdAndMinAmount(serverId, in.minAmount())) {
            throw ApiException.conflict("DUPLICATE_RULE", "같은 금액의 규칙이 이미 있습니다.");
        }
        r.update(in.minAmount(), in.label(), in.actionType(), blankToNull(in.param()), in.cooldownSeconds());
        return toDto(s);
    }

    @Transactional
    public ServerDto deleteRule(AuthUser me, Long serverId, Long ruleId) {
        McServer s = owned(me, serverId);
        DonationRule r = ruleRepository.findById(ruleId).filter(x -> x.getServer().getId().equals(serverId))
                .orElseThrow(() -> ApiException.notFound("규칙"));
        ruleRepository.delete(r);
        return toDto(s);
    }

    public record RuleInput(long minAmount, String label, DonationRule.ActionType actionType, String param, int cooldownSeconds) {}

    // ---------- 반응 기록 ----------

    @Transactional(readOnly = true)
    public List<ActionDto> actions(AuthUser me, Long serverId) {
        owned(me, serverId);
        return actionRepository.findTop50ByServerIdOrderByIdDesc(serverId).stream().map(ActionDto::from).toList();
    }

    @Transactional
    public void retryAction(AuthUser me, Long serverId, Long actionId) {
        owned(me, serverId);
        gameActionService.retry(serverId, actionId);
    }

    // ---------- 내부 ----------

    private void validate(RuleInput in) {
        if (in.actionType() == null) throw ApiException.badRequest("INVALID_RULE", "반응 종류를 골라 주세요.");
        if (in.minAmount() < 1000 || in.minAmount() > 10_000_000) throw ApiException.badRequest("INVALID_RULE", "기준 금액은 1,000원 ~ 10,000,000원입니다.");
        if (in.label() == null || in.label().isBlank() || in.label().length() > 30) throw ApiException.badRequest("INVALID_RULE", "이름은 1~30자입니다.");
        if (in.cooldownSeconds() < 0 || in.cooldownSeconds() > 3600) throw ApiException.badRequest("INVALID_RULE", "쿨타임은 0~3600초입니다.");
        if (in.actionType() == DonationRule.ActionType.COMMAND && (in.param() == null || in.param().isBlank())) {
            throw ApiException.badRequest("INVALID_RULE", "명령어를 입력해 주세요.");
        }
        if (in.param() != null && in.param().length() > 200) throw ApiException.badRequest("INVALID_RULE", "추가 값은 200자 이하입니다.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private McServer owned(AuthUser me, Long serverId) {
        McServer s = serverRepository.findById(serverId).orElseThrow(() -> ApiException.notFound("서버"));
        if (!s.isOwnedBy(me.id())) throw ApiException.forbidden("서버장만 할 수 있습니다.");
        return s;
    }

    private McMember myMembership(AuthUser me, Long memberId) {
        McMember m = memberRepository.findById(memberId).orElseThrow(() -> ApiException.notFound("초대"));
        if (!m.getUser().getId().equals(me.id())) throw ApiException.forbidden("본인의 초대만 처리할 수 있습니다.");
        return m;
    }

    private ServerDto toDto(McServer s) {
        Long id = s.getId();
        List<MemberDto> members = memberRepository.findByServer(id).stream()
                .map(m -> new MemberDto(m.getId(), m.getMcName(), m.getUser().getId(), m.getUser().getUsername(),
                        m.getUser().getNickname(), m.getStatus(), registry.isOnline(id, m.getMcName())))
                .toList();
        List<RuleDto> rules = ruleRepository.findByServerIdOrderByMinAmountAsc(id).stream().map(RuleDto::from).toList();
        boolean codeValid = s.isLinkCodeValid(Instant.now());
        return new ServerDto(id, s.getName(), s.isLinked(), registry.isConnected(id),
                codeValid ? s.getLinkCode() : null, codeValid ? s.getLinkCodeExpiresAt() : null,
                List.copyOf(registry.onlinePlayers(id)), members, rules);
    }
}
