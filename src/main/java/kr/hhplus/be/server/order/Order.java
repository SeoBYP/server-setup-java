package kr.hhplus.be.server.order;

import jakarta.persistence.*;
import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.order.DTO.OrderRequest; // OrderRequest가 필요할 수 있어 일단 유지
import kr.hhplus.be.server.product.Product;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    @Convert(converter = OrderStatusConverter.class) // OrderStatusConverter 클래스는 제공되지 않았지만 유지
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
    List<OrderItem> orderItems; // OrderItem 엔티티는 제공되지 않았지만 유지

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
        this.status = OrderStatus.ORDERED; // 기본값 설정 (OrderStatus enum이 있다고 가정)
        this.totalAmount = BigDecimal.ZERO;
        this.paidAmount = BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
    }

    public Order(Long userId, BigDecimal totalAmount, List<OrderItemRequest> itemRequests, Map<Long, Product> productMap) {
        this.userId = userId;
        this.status = OrderStatus.ORDERED;
        this.totalAmount = totalAmount;
        this.paidAmount = totalAmount; // 단순 결제는 totalAmount와 동일하다고 가정
        this.createdAt = LocalDateTime.now();

        // 주문 항목 리스트 생성 및 양방향 연관 관계 설정
        List<OrderItem> createdOrderItems = itemRequests.stream()
                .map(request -> {
                    Product product = productMap.get(request.productId()); // productId()는 OrderItemRequest 레코드의 접근자
                    if (product == null) {
                        throw new IllegalArgumentException("Product not found for ID: " + request.productId());
                    }

                    // OrderItem 생성자 (Order order, Long productId, Integer quantity, BigDecimal unitPrice) 사용
                    OrderItem item = new OrderItem(
                            this, // Order 엔티티 자신을 전달하여 양방향 연관 관계 설정
                            request.productId(),
                            request.quantity(),
                            product.getPrice() // Product 엔티티에 getPrice()가 있다고 가정
                    );
                    return item;
                })
                .collect(Collectors.toList());

        setOrderItems(createdOrderItems);
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