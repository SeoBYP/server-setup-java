package kr.hhplus.be.server.coupon.exception;

public class CouponAlreadyUsedException extends RuntimeException {
    public CouponAlreadyUsedException(String message) {
        super(message);
    }

    public CouponAlreadyUsedException() {
        super("ALREADY_USED");
    }
}
