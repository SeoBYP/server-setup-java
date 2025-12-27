package kr.hhplus.be.server.coupon;

import jakarta.persistence.*;
import kr.hhplus.be.server.coupon.exception.CouponExpiredException;
import kr.hhplus.be.server.coupon.exception.CouponNotYetAvailableException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "type", nullable = false)
    @Convert(converter = CouponTypeConverter.class)
    private CouponType type;

    @Column(name = "value", nullable = false)
    private BigDecimal value;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private Integer remainingQuantity;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Coupon() { }

    public Coupon(String code, CouponType type, BigDecimal value, LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime createdAt) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.totalQuantity = 10;
        this.remainingQuantity = 10;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = createdAt;
    }

    public Coupon(String code, CouponType type, BigDecimal value) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.totalQuantity = 10;
        this.remainingQuantity = 10;
        this.startsAt = LocalDateTime.of(1000, 1, 1, 0, 0);
        this.endsAt = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        this.createdAt = LocalDateTime.now();
    }

    // ✅ 추가: 총 수량을 지정할 수 있는 생성자(remaining도 동일하게 초기화)
    public Coupon(String code, CouponType type, BigDecimal value, int totalQuantity) {
        if (totalQuantity <= 0) throw new IllegalArgumentException("totalQuantity>0");

        this.code = code;
        this.type = type;
        this.value = value;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.startsAt = LocalDateTime.of(1000, 1, 1, 0, 0);
        this.endsAt = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        this.createdAt = LocalDateTime.now();
    }

    public void validateClaimable() {
        LocalDateTime now = LocalDateTime.now();

        if (this.startsAt != null && now.isBefore(this.startsAt)) {
            throw new CouponNotYetAvailableException("아직 쿠폰 발급 기간이 아닙니다. 시작일: " + this.startsAt);
        }

        if (this.endsAt != null && now.isAfter(this.endsAt)) {
            throw new CouponExpiredException("쿠폰 발급 기간이 만료되었습니다. 만료일: " + this.endsAt);
        }
    }

    public BigDecimal calculateDiscountedAmount(BigDecimal originalAmount) {
        if (this.type == CouponType.PERCENT) {
            BigDecimal discountRate = this.value.divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal discountAmount = originalAmount.multiply(discountRate);

            if (discountAmount.compareTo(originalAmount) > 0) {
                discountAmount = originalAmount;
            }
            return originalAmount.subtract(discountAmount);

        } else if (this.type == CouponType.FIXED) {
            BigDecimal discountedAmount = originalAmount.subtract(this.value);
            return discountedAmount.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : discountedAmount;
        }
        return originalAmount;
    }

    public Long getCouponId() { return couponId; }
    public String getCode() { return code; }
    public CouponType getType() { return type; }
    public BigDecimal getValue() { return value; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getEndsAt() { return endsAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public Integer getRemainingQuantity() { return remainingQuantity; }
}