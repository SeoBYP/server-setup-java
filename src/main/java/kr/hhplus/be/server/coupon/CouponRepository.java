package kr.hhplus.be.server.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Query("select w from Coupon w where w.couponId = :couponId")
    Optional<Coupon> findForUpdate(@Param("couponId") Long couponId);

    // DB Lock 삭제
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.couponId = :couponId")
    Optional<Coupon> findCouponWithPessimisticLock(@Param("couponId") Long couponId);

    // ✅ remainingQuantity > 0 인 경우에만 1 감소 (선착순 핵심)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Coupon c
           set c.remainingQuantity = c.remainingQuantity - 1
         where c.couponId = :couponId
           and c.remainingQuantity > 0
    """)
    int decrementRemainingIfAvailable(Long couponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Coupon c
           set c.remainingQuantity = c.remainingQuantity + 1
         where c.couponId = :couponId
    """)
    int incrementRemaining(Long couponId);

}
