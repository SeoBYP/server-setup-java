package kr.hhplus.be.server.coupon.exception;

public class CouponExpiredException extends RuntimeException {

    public CouponExpiredException(String message) {
        super(message);
    }

    public CouponExpiredException() {
        super("EXPIRED_COUPON");
    }
}
