package kr.hhplus.be.server.coupon.exception;

public class CouponNotFoundException extends RuntimeException {
    public CouponNotFoundException(String message) {
        super(message);
    }

    public CouponNotFoundException(){
      super("COUPON_NOT_FOUND");
    }
}
