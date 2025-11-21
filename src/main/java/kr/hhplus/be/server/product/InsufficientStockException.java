package kr.hhplus.be.server.product;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException() {
        super("INSUFFICIENT_STOCK");
    }
}
