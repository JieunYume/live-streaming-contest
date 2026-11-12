package com.livecraft.minecraft;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DonationRuleRepository extends JpaRepository<DonationRule, Long> {

    List<DonationRule> findByServerIdOrderByMinAmountAsc(Long serverId);

    /** 금액 이하에서 가장 큰 기준 금액의 규칙 */
    Optional<DonationRule> findFirstByServerIdAndMinAmountLessThanEqualOrderByMinAmountDesc(Long serverId, long amount);

    boolean existsByServerIdAndMinAmount(Long serverId, long minAmount);

    void deleteByServerId(Long serverId);
}
