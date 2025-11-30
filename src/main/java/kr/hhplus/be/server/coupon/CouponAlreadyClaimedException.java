package kr.hhplus.be.server.coupon;

public class CouponAlreadyClaimedException extends RuntimeException {
    public CouponAlreadyClaimedException(String message) {
        super(message);
    }

    public CouponAlreadyClaimedException() {
        super("ALREADY_CLAIMED");
    }
}
