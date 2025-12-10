package kr.hhplus.be.server.outbox.DTO;

import java.math.BigDecimal;
import java.util.List;

public class OrderCreatedEventPayload {

    public static class Item {
        private Long productId;
        private Integer quantity;

        public Item() {}
        public Item(Long productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public Long getProductId() { return productId; }
        public Integer getQuantity() { return quantity; }
    }

    private Long orderId;
    private Long userId;
    private BigDecimal paidAmount;
    private List<Item> items;

    public OrderCreatedEventPayload() {}

    public OrderCreatedEventPayload(Long orderId, Long userId, BigDecimal paidAmount, List<Item> items) {
        this.orderId = orderId;
        this.userId = userId;
        this.paidAmount = paidAmount;
        this.items = items;
    }

    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public List<Item> getItems() { return items; }
}