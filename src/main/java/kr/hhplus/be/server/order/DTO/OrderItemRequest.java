package kr.hhplus.be.server.order.DTO;

public class OrderItemRequest {
    private final Long productId;

    private final Integer quantity;

    public OrderItemRequest(Long productId, Integer quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
