package kr.hhplus.be.server.coupon.handler;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
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
}
