package kr.hhplus.be.server.coupon.exception;

public class UserCouponNotFoundException extends RuntimeException {
    public UserCouponNotFoundException(String message) {
        super(message);
    }

    public UserCouponNotFoundException(){
      super("USER_COUPON_NOT_FOUND");
    }
}
