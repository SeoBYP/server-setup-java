package kr.hhplus.be.server.coupon;

public class CouponAlreadyUsedException extends RuntimeException {
    public CouponAlreadyUsedException(String message) {
        super(message);
    }

    public CouponAlreadyUsedException() {
        super("ALREADY_USED");
    }
}
