package kr.hhplus.be.server.coupon;

public class CouponExpiredException extends RuntimeException {

    public CouponExpiredException(String message) {
        super(message);
    }

    public CouponExpiredException() {
        super("EXPIRED_COUPON");
    }
}
