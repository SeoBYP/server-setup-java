package kr.hhplus.be.server.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Product() {
    }

    public Product(Long productId,
                   String name,
                   BigDecimal price,
                   Integer stock) {
        // 도메인 규칙: 가격은 null이 아니며, 0보다 커야 함 (0원 상품은 정책에 따라 허용 가능)
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("상품 가격은 0 미만일 수 없습니다.");
        }

        // 재고도 0 미만 방지
        if (stock == null || stock < 0) {
            throw new IllegalArgumentException("상품 재고는 0 미만일 수 없습니다.");
        }

        this.productId = productId;
        this.name = name;
        this.price = price == null ? BigDecimal.ZERO : price;
        this.stock = stock;
    }

    public Long getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Integer getStock() {
        return stock;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void charge(Integer amount)
    {
        if(amount == null || amount <= 0)
            throw new IllegalArgumentException("amount>0");
        this.stock += amount;
    }

    public void debit(Integer amount)
    {
        if(amount == null || amount <= 0)
            throw new IllegalArgumentException("amount>0");
        if(this.stock < amount)
            throw new InsufficientStockException();
        this.stock -= amount;
    }

    @PrePersist
    void onCreate(){
        if(createdAt == null)
            createdAt = LocalDateTime.now();
    }
}
