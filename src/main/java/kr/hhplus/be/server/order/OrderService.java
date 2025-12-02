package kr.hhplus.be.server.order;

import kr.hhplus.be.server.coupon.Coupon;
import kr.hhplus.be.server.coupon.CouponService;
import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.order.DTO.OrderRequest;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductService;
import kr.hhplus.be.server.product.popularProduct.PopularProduct;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import kr.hhplus.be.server.wallet.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.PublicKey;
import java.util.ArrayList;
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

    @Autowired
    private PopularProductRepository popularProductRepository;

    @Autowired
    private CouponService couponService;

    @Autowired
    private OutboxRepository outboxRepository; // 1. OutboxRepository 주입

    @Transactional // 단일 트랜잭션으로 원자성 보장
    public Order createOrder(Long userId, List<OrderItemRequest> orderItems, Long userCouponId) {

        // 1. 주문 유효성 검사
        if (orderItems == null || orderItems.isEmpty()) {
            throw new IllegalArgumentException("ORDER_ITEMS_EMPTY");
        }

        // 2. 상품 조회, 재고 확인/차감, 총액 계산 (동시성 제어 - 비관적 락)
        BigDecimal totalAmount = BigDecimal.ZERO; // 쿠폰 적용 전 총 금액
        Map<Long, Product> productMap = new HashMap<>();
        for (OrderItemRequest item : orderItems) {
            Long productId = item.productId();
            Integer quantity = item.quantity();
            Product product = productService.debit(productId, quantity);
            BigDecimal itemPrice = product.getPrice().multiply(new BigDecimal(quantity));
            totalAmount = totalAmount.add(itemPrice);
            productMap.put(productId, product);
        }

        BigDecimal finalPaymentAmount = totalAmount; // 최종 결제 금액
        Long usedCouponId = null;

        // 3. **쿠폰 사용 로직 추가**
        if (userCouponId != null) {
            // A. 사용자 쿠폰 유효성 검사 및 락 획득 (UserCouponRepository에 정의된 findForUpdate 사용)
            UserCoupon userCoupon = couponService.validateAndLockUserCoupon(userCouponId, userId);

            // B. 쿠폰 정보 로드 및 유효 기간 재확인
            Coupon coupon = couponService.getCouponById(userCoupon.getCouponId());
            coupon.validateClaimable(); // 유효 기간 확인

            // C. 할인 금액 계산
            finalPaymentAmount = coupon.calculateDiscountedAmount(totalAmount);

            // D. 쿠폰 사용 처리 (상태를 USED로 변경)
            userCoupon.use(); // UserCoupon 엔티티 내부에서 상태 변경
            couponService.saveUserCoupon(userCoupon); // DB에 반영
            usedCouponId = userCoupon.getUserCouponId();
        }

        // 3. 잔액 확인 및 결제 (동시성 제어 - 비관적 락)
        // WalletService.debit()은 내부적으로 SELECT FOR UPDATE를 사용하고, 잔액 부족 시 예외 발생
        walletService.debit(userId, totalAmount);

        // 4. 주문 엔티티 생성 및 저장
        // ⭐ 수정 부분: Order.java의 생성자 시그니처 (userId, totalAmount, itemRequests, productMap)에 맞게 호출
        Order newOrder = new Order(userId, totalAmount, orderItems, productMap);
        Order savedOrder = orderRepository.save(newOrder);

        // 5. 데이터 플랫폼 전송 (트랜잭션 커밋 직전에 실행)
        recordOutboxEvent(savedOrder); // 2. Outbox 기록 메서드 호출

        // 6. 인기 상품 판매량 업데이트 (비동기 배치 대신 실시간 반영을 선택할 경우)
        orderItems.forEach(item ->
                updatePopularProductSales(item.productId(), item.quantity())
        );

        return savedOrder;
    }

    private void recordOutboxEvent(Order order) {
        // Order 객체를 외부 시스템이 요구하는 JSON 형태로 변환
        // 실제로는 별도의 DTO나 ObjectMapper를 사용해 JSON 문자열로 변환합니다.
        String orderJsonPayload = generateOrderPayload(order);

        Outbox outboxEvent = new Outbox("ORDER",
                order.getOrderId().toString(), // 집계 ID로 Order ID 사용
                orderJsonPayload);

        outboxRepository.save(outboxEvent);
    }

    private String generateOrderPayload(Order order) {
        // 단순화된 JSON 문자열 Mock
        return String.format("{\"orderId\": %d, \"userId\": %d, \"paidAmount\": %s, \"itemCount\": %d}",
                order.getOrderId(),
                order.getUserId(),
                order.getPaidAmount().toString(),
                order.getOrderItems().size());
    }

    public Order createOrder(OrderRequest request) {
        return createOrder(request.userId(),request.items(),request.userCouponId());
    }

    private void updatePopularProductSales(Long productId, Integer quantity) {
        // productId로 PopularProduct를 조회하며 비관적 잠금 적용 (findForUpdateByProductId 사용)
        popularProductRepository.findForUpdateByProductId(productId).ifPresentOrElse(popularProduct -> {
            popularProduct.addSalesQuantity(quantity);
            popularProductRepository.save(popularProduct);
        }, () -> {
            // 해당 상품이 인기 상품 목록에 없으면 새로 생성하여 추가
            PopularProduct newPopularProduct = new PopularProduct(productId, quantity);
            popularProductRepository.save(newPopularProduct);
        });
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
