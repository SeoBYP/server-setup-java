package kr.hhplus.be.server.product.popularProduct;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "popular_products")
public class PopularProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sales_quantity", nullable = false)
    private Integer salesQuantity;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    public PopularProduct(Long productId, Integer sales_quantity) {
        this.productId = productId;
        this.salesQuantity = sales_quantity;
        this.recordedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getSalesQuantity() {
        return salesQuantity;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void addSalesQuantity(Integer quantity) {
        this.salesQuantity += quantity;
        this.recordedAt = LocalDateTime.now(); // 업데이트 시간 기록
    }
}
