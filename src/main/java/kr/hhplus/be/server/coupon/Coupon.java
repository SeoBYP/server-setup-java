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
    @GeneratedValue(strategy = GenerationType.IDENTITY) // <--- 이 부분이 핵심
    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "type", nullable = false)
    @Convert(converter = CouponTypeConverter.class)
    private CouponType type;

    @Column(name = "value", nullable = false)
    private BigDecimal value;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Coupon() {

    }

    public Coupon(String code, CouponType type, BigDecimal value, LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime createdAt) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = createdAt;
    }

    public Coupon(String code, CouponType type, BigDecimal value) {
        this.code = code;
        this.type = type;
        this.value = value;
        // MySQL이 허용하는 가장 이른 날짜 또는 현실적인 값으로 설정
        this.startsAt = LocalDateTime.of(1000, 1, 1, 0, 0);
        // MySQL이 허용하는 가장 늦은 날짜 또는 현실적인 값으로 설정
        this.endsAt = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        this.createdAt = LocalDateTime.now();
    }

    // 발급 가능 여부를 검증하는 도메인 메서드
    public void validateClaimable(){
        LocalDateTime now = LocalDateTime.now();

        // 1. 발급 시작일 검증: 시작일이 현재 시간보다 '이후'면 예외
        if (this.startsAt != null && now.isBefore(this.startsAt)) {
            throw new CouponNotYetAvailableException("아직 쿠폰 발급 기간이 아닙니다. 시작일: " + this.startsAt);
        }

        // 2. 만료일 검증: 만료일이 현재 시간과 '같거나 이전'이면 예외
        // endsAt.isEqual(now)는 초 단위까지 완벽히 같아야 하므로,
        // isAfter(endsAt) 또는 isBefore(endsAt.plusNanos(1))을 사용하거나
        // 아래와 같이 isBefore()를 반대로 사용하여 처리하는 것이 일반적입니다.
        if (this.endsAt != null && now.isAfter(this.endsAt)) {
            throw new CouponExpiredException("쿠폰 발급 기간이 만료되었습니다. 만료일: " + this.endsAt);
        }
    }

    public BigDecimal calculateDiscountedAmount(BigDecimal originalAmount) {
        if (this.type == CouponType.PERCENT) {
            // 퍼센트 할인 (예: value가 10이면 10% 할인)
            BigDecimal discountRate = this.value.divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal discountAmount = originalAmount.multiply(discountRate);

            // 할인 금액이 원 금액보다 커서 최종 금액이 음수가 되는 것을 방지
            if (discountAmount.compareTo(originalAmount) > 0) {
                discountAmount = originalAmount;
            }
            return originalAmount.subtract(discountAmount);

        } else if (this.type == CouponType.FIXED) {
            // 고정 금액 할인
            BigDecimal discountedAmount = originalAmount.subtract(this.value);
            // 최종 금액이 0 미만인 경우 0으로 처리
            return discountedAmount.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : discountedAmount;
        }
        return originalAmount; // 쿠폰 타입이 이상하면 원 금액 반환
    }

    public Long getCouponId() {
        return couponId;
    }

    public String getCode() {
        return code;
    }

    public CouponType getType() {
        return type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
