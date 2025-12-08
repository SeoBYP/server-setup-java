package kr.hhplus.be.server.coupon;

import jakarta.persistence.*;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyUsedException;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_coupons")
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id",nullable = false)
    private Long userCouponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "coupon_id",unique = true,nullable = false)
    private Long couponId;

    @Column(name = "status",nullable = false)
    @Convert(converter = CouponStatusConverter.class)
    private CouponStatus couponStatus;

    @Column(name = "claimed_at",nullable = false)
    private LocalDateTime claimedAt;

    @Column(name = "used_at",nullable = true)
    private LocalDateTime usedAt;

    public UserCoupon() {
    }

    public UserCoupon(Long userId, Long couponId, CouponStatus couponStatus) {
        this.userId = userId;
        this.couponId = couponId;
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


    public void use() {
        if(couponStatus == CouponStatus.USED)
            throw new CouponAlreadyUsedException();
        couponStatus = CouponStatus.USED;
        usedAt = LocalDateTime.now();
    }
}
