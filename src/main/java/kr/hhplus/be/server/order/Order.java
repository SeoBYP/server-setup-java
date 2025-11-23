package kr.hhplus.be.server.order;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // <--- 이 부분이 핵심
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "status", nullable = false)
    @Convert(converter = OrderStatusConverter.class)
    private OrderStatus status;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "user_coupon_id")
    private BigInteger userCouponId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    List<OrderItem> orderItems;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected Order() {}

    public Order(Long userId, OrderStatus status, BigDecimal totalAmount, BigDecimal discountAmount, BigDecimal paidAmount, String idempotencyKey, BigInteger userCouponId) {
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.paidAmount = paidAmount;
        this.idempotencyKey = idempotencyKey;
        this.userCouponId = userCouponId;
        this.createdAt = LocalDateTime.now();
    }

    public Order(Long userId) {
        this.userId = userId;
        this.status = OrderStatus.ORDERED; // 기본값 설정
        this.totalAmount = BigDecimal.ZERO;
        this.paidAmount = BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
    }

    public Order(Long userId, OrderStatus status, BigDecimal totalAmount, BigDecimal paidAmount)
    {
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.paidAmount = paidAmount;
        this.createdAt = LocalDateTime.now(); // 생성 시간 자동 설정
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigInteger getUserCouponId() {
        return userCouponId;
    }

    public List<OrderItem> getOrderItems() {
        return orderItems;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setOrderItems(List<OrderItem> orderItems) {
        this.orderItems = orderItems;
    }

    @PrePersist
    void onCreate(){
        if(createdAt == null)
            createdAt = LocalDateTime.now();
    }
}
