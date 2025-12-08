package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderRequest;
import kr.hhplus.be.server.order.DTO.OrderResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
        // Service 로직 실행
        Order order = orderService.createOrder(request);

        // HTTP 201 Created와 함께 주문 응답 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

}
