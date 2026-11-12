package com.livecraft.donation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface DonationRepository extends JpaRepository<Donation, Long> {

    /** 승인 확정은 이 잠금 안에서만 한다 → 확정 요청과 웹훅이 동시에 와도 한 번만 적립 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from Donation d join fetch d.broadcast b join fetch d.donor join fetch d.streamer
            where d.orderId = :orderId
            """)
    Optional<Donation> findForUpdate(String orderId);

    @Query("""
            select d from Donation d join fetch d.donor
            where d.broadcast.id = :broadcastId and d.status = com.livecraft.donation.Donation.Status.PAID
            order by d.paidAt desc
            """)
    List<Donation> findPaid(Long broadcastId);

    @Query("""
            select coalesce(sum(d.amount), 0) from Donation d
            where d.broadcast.id = :broadcastId and d.status = com.livecraft.donation.Donation.Status.PAID
            """)
    long sumPaid(Long broadcastId);
}
