package kr.hhplus.be.server.coupon.exception;

public class CouponOwnerMismatchException extends RuntimeException {
    public CouponOwnerMismatchException(String message) {
        super(message);
    }

  public CouponOwnerMismatchException() {
      super("COUPON_OWNER_MISSMATCH");
  }
}
