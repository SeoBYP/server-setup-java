package kr.hhplus.be.server.coupon.handler;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyUsedException;
import kr.hhplus.be.server.coupon.exception.CouponSoldOutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CouponExceptionHandler {

    @ExceptionHandler(CouponAlreadyClaimedException.class)
    public ResponseEntity<String> handleAlreadyClaimed(CouponAlreadyClaimedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(CouponAlreadyUsedException.class)
    public ResponseEntity<String> handleAlreadyUsed(CouponAlreadyUsedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(CouponSoldOutException.class)
    public ResponseEntity<String> handleSoldOut(CouponSoldOutException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(e.getMessage()); // 또는 CONFLICT(409) 선택 가능
    }
}
