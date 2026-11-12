package com.livecraft.minecraft;

import com.livecraft.global.BaseTimeEntity;
import com.livecraft.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버 화이트리스트 한 줄 = 마크 닉네임 + 초대한 플랫폼 계정.
 * 스트리머가 초대를 수락해야 후원이 이 닉네임으로 연결된다.
 */
@Getter
@Entity
@Table(name = "mc_members", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "mc_name_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McMember extends BaseTimeEntity {

    public enum Status { INVITED, ACCEPTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private McServer server;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 16)
    private String mcName;

    /** 마크 닉네임은 대소문자를 구분하지 않으므로 소문자로 비교한다 */
    @Column(name = "mc_name_key", nullable = false, length = 16)
    private String mcNameKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    public McMember(McServer server, User user, String mcName) {
        this.server = server;
        this.user = user;
        this.mcName = mcName;
        this.mcNameKey = key(mcName);
        this.status = Status.INVITED;
    }

    public static String key(String mcName) {
        return mcName.toLowerCase(java.util.Locale.ROOT);
    }

    public void accept() {
        status = Status.ACCEPTED;
    }
}
