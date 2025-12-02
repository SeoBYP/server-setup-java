package kr.hhplus.be.server.wallet.DTO;

import java.math.BigDecimal;

public class ChargeRequest {
    private final BigDecimal amount;

    public ChargeRequest(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
