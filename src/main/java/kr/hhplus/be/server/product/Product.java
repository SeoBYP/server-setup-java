package kr.hhplus.be.server.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "products",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_products_name", columnNames = {"name"})
        }
)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id", nullable = false, columnDefinition = "BIGINT AUTO_INCREMENT")
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

    public Product(String name, BigDecimal price, Integer stock) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name_required");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price>=0");
        }
        if (stock == null || stock < 0) {
            throw new IllegalArgumentException("stock>=0");
        }

        this.name = name;
        this.price = price;
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

    public void charge(Integer amount) {
        if (amount == null || amount <= 0)
            throw new IllegalArgumentException("amount>0");
        this.stock += amount;
    }

    public void debit(Integer amount) {
        if (amount == null || amount <= 0)
            throw new IllegalArgumentException("amount>0");
        if (this.stock < amount)
            throw new InsufficientStockException();
        this.stock -= amount;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null)
            createdAt = LocalDateTime.now();
    }
}