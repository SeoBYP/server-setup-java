package kr.hhplus.be.server.order.DTO;

import java.util.List;

public record OrderRequest(
        Long userId,
        List<OrderItemRequest> items,
        Long userCouponId,
        String idempotencyKey
) {}