package kr.hhplus.be.server.order.DTO;

import kr.hhplus.be.server.order.Order;
import java.math.BigDecimal;


public record OrderResponse(
        Long orderId,
        Long userId,
        BigDecimal totalAmount
        // 필요에 따라 주문 상세 목록 등 추가 가능
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getUserId(),
                order.getTotalAmount()
        );
    }
}