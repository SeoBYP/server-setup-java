package kr.hhplus.be.server.coupon;

import jakarta.persistence.LockModeType;
import kr.hhplus.be.server.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserCoupon w where w.userCouponId = :userCouponId")
    Optional<UserCoupon> findForUpdate(@Param("userCouponId") Long userCouponId);

    // 특정 사용자가 특정 쿠폰을 이미 발급(CLAIMED) 받았는지 확인
    boolean existsByUserIdAndCouponIdAndCouponStatus(Long userId, Long couponId, CouponStatus status);

    /**
     * userId로 해당 사용자가 발급받은 모든 쿠폰 목록을 조회합니다.
     * Spring Data JPA가 자동으로 다음 쿼리를 생성합니다.
     * SELECT * FROM user_coupons WHERE user_id = ?
     */
    List<UserCoupon> findAllByUserId(Long userId);
}
