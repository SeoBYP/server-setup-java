package kr.hhplus.be.server.order;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unit_price;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal;

    public OrderItem() {
    }

    public OrderItem(Long orderItemId, Order order, Long productId, BigDecimal unit_price, Integer quantity, BigDecimal subtotal) {
        this.orderItemId = orderItemId;
        this.order = order;
        this.productId = productId;
        this.unit_price = unit_price;
        this.quantity = quantity;
        this.subtotal = subtotal;
    }

    public OrderItem(Order order, Long productId, Integer quantity, BigDecimal unit_price) {
        this.order = order;

        this.productId = productId;
        this.quantity = quantity;
        this.unit_price = unit_price;
        this.subtotal = unit_price.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Order getOrder() {
        return order;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getUnit_price() {
        return unit_price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
