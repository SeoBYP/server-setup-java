package kr.hhplus.be.server.coupon.exception;

public class CouponSoldOutException extends RuntimeException {
    public CouponSoldOutException() {
        super("쿠폰이 모두 소진되었습니다.");
    }

    public CouponSoldOutException(String message) {
        super(message);
    }
}