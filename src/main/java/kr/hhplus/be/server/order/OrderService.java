package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductService;
import kr.hhplus.be.server.wallet.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private ProductService productService;

    @Transactional
    public Order createOrder(Long userId, BigDecimal usedPointer, List<OrderItemRequest> orderItems) {

        // 1, 상품 조회 및 재고 차감 (이 과정에서 동시성 제어가 필수)
        BigDecimal paid = BigDecimal.ZERO;
        Map<Long, Product> paidProductMap = new HashMap<Long,Product>();
        for (OrderItemRequest orderItem : orderItems) {
            var paidProduct = productService.debit(orderItem.getProductId(),orderItem.getQuantity());
            paid = paid.add(paidProduct.getPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity())));
            paidProductMap.put(orderItem.getProductId(), paidProduct);
        }

        // 2, 포인트 차감
        walletService.debit(userId,usedPointer);

        // 3, 주문 생성
        Order newOrder = new Order(userId, OrderStatus.ORDERED, paid, usedPointer);
        List<OrderItem> items = orderItems.stream()
                .map(itemRequest -> new OrderItem(
                        newOrder,
                        itemRequest.getProductId(),
                        itemRequest.getQuantity(),
                        paidProductMap.get(itemRequest.getProductId()).getPrice()
                )).toList();
        newOrder.setOrderItems(items); // Order 객체에 OrderItem 리스트 설정
        return orderRepository.save(newOrder);
    }

    @Transactional
    public Order getOrder(Long orderId) {
        var order = orderRepository.findById(orderId);
        if (order.isEmpty())
            throw new IllegalArgumentException("ORDER_NOT_FOUND");
        return order.get();
    }

    @Transactional
    public List<Order> getOrders(List<Long> orderIds) {
        return orderRepository.findAllById(orderIds);
    }

    @Transactional
    public List<Order> getOrders(Long userId) {
        return orderRepository.findAll()
                .stream()
                .filter(order -> order.getUserId().equals(userId))
                .toList();
    }


    @Transactional
    public List<Order> getOrders() {
        return orderRepository.findAll();
    }

    @Transactional
    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(userId))
                .collect(Collectors.toList());
    }
}
