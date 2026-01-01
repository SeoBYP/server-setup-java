package kr.hhplus.be.server.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    @Query("select w from UserCoupon w where w.userCouponId = :userCouponId")
    Optional<UserCoupon> findForUpdate(@Param("userCouponId") Long userCouponId);

    boolean existsByUserIdAndCouponIdAndCouponStatus(Long userId, Long couponId, CouponStatus status);

    Optional<UserCoupon> findByUserIdAndCouponIdAndCouponStatus(Long userId, Long couponId, CouponStatus couponStatus);

    List<UserCoupon> findAllByUserId(Long userId);

    Optional<UserCoupon> findByRequestId(String requestId);

    Optional<UserCoupon> findByUserIdAndCouponId(Long userId, Long couponId);
}
