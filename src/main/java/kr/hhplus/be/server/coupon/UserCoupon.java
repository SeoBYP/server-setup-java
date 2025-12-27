package kr.hhplus.be.server.coupon;

import jakarta.persistence.*;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyUsedException;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_coupons",
        uniqueConstraints = {
                // ✅ 유저는 동일 쿠폰을 1번만 발급
                @UniqueConstraint(name = "uk_user_coupon_user_coupon", columnNames = {"user_id", "coupon_id"}),
                // ✅ Kafka 재처리/중복 소비 멱등
                @UniqueConstraint(name = "uk_user_coupon_request_id", columnNames = {"request_id"})
        }
)
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id",nullable = false)
    private Long userCouponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    // 멱등성 키
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "status",nullable = false)
    @Convert(converter = CouponStatusConverter.class)
    private CouponStatus couponStatus;

    @Column(name = "claimed_at",nullable = false)
    private LocalDateTime claimedAt;

    @Column(name = "used_at",nullable = true)
    private LocalDateTime usedAt;

    public UserCoupon() {
    }

    public UserCoupon(Long userId, Long couponId,
                      String requestId, CouponStatus couponStatus) {
        this.userId = userId;
        this.couponId = couponId;
        this.requestId = requestId;
        this.couponStatus = couponStatus;
        this.claimedAt = LocalDateTime.now();
        this.usedAt = null;
    }

    public Long getUserCouponId() {
        return userCouponId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCouponId() {
        return couponId;
    }

    public CouponStatus getCouponStatus() {
        return couponStatus;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public void use() {
        if(couponStatus == CouponStatus.USED)
            throw new CouponAlreadyUsedException();
        couponStatus = CouponStatus.USED;
        usedAt = LocalDateTime.now();
    }
}
