package kr.hhplus.be.server.coupon;

public class CouponNotYetAvailableException extends RuntimeException {
    public CouponNotYetAvailableException(String message) {
        super(message);
    }

    public CouponNotYetAvailableException(){
        super("COUPON_NOT_AVAILABLE");
    }
}
