package kr.hhplus.be.server.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Coupon w where w.couponId = :couponId")
    Optional<Coupon> findForUpdate(@Param("couponId") Long couponId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.couponId = :couponId")
    Optional<Coupon> findCouponWithPessimisticLock(@Param("couponId") Long couponId);
}
