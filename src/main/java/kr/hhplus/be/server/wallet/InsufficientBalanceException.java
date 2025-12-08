package kr.hhplus.be.server.wallet;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException() {
        super("INSUFFICIENT_BALANCE");
    }
}
