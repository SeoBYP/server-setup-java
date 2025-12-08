package kr.hhplus.be.server.order.DTO;

// ⭐ 수정된 부분: 클래스 대신 Java Record로 변경하여 간결하게 만듭니다.
public record OrderItemRequest(
        Long productId,
        Integer quantity
) {
}