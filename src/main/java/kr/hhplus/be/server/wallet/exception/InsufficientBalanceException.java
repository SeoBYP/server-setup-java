package kr.hhplus.be.server.wallet.exception;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException() {
        super("INSUFFICIENT_BALANCE");
    }
}
