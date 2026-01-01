package kr.hhplus.be.server.wallet.handler;

import kr.hhplus.be.server.wallet.exception.InsufficientBalanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WalletExceptionHandler {

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<String> handleInsufficientBalance(InsufficientBalanceException e) {
        // body는 메시지(INSUFFICIENT_BALANCE) 그대로 사용
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
