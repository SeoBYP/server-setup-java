package kr.hhplus.be.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wallets")
public class Wallet {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "balance",nullable = false, precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Wallet(){}

    public Wallet(Long userId, BigDecimal initial) {
        this.userId = userId;
        this.balance = initial == null ? BigDecimal.ZERO : initial;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void charge(BigDecimal amount)
    {
        if(amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount>0");
        this.balance = this.balance.add(amount);
    }

    public void debit(BigDecimal amount)
    {
        if(amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount>0");
        if (this.balance.compareTo(amount) < 0) throw new InsufficientBalanceException();
        this.balance = this.balance.subtract(amount);
    }

    @PrePersist
    void onCreate(){
        if(createdAt == null)
            createdAt = LocalDateTime.now();
    }
}
