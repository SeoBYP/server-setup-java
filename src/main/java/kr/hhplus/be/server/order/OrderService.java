package kr.hhplus.be.server.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.coupon.Coupon;
import kr.hhplus.be.server.coupon.CouponService;
import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.order.DTO.OrderRequest;
import kr.hhplus.be.server.outbox.DTO.OrderCreatedEventPayload;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductService;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import kr.hhplus.be.server.wallet.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional // 단일 트랜잭션으로 원자성 보장
    public Order createOrderTx(Long userId, List<OrderItemRequest> orderItems, Long userCouponId, String idempotencyKey) {
        // 0. idempotencyKey 중복 검사 (Idempotency 테이블 or Order에 unique 컬럼)
        if(idempotencyKey == null || idempotencyKey.isBlank()){
            throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
        }

        var exist = orderRepository.findByIdempotencyKey(idempotencyKey);
        if(exist.isPresent())
        {
            return exist.get();
        }

        try {
            // 1. 주문 유효성 검사
            if (orderItems == null || orderItems.isEmpty()) {
                throw new IllegalArgumentException("ORDER_ITEMS_EMPTY");
            }

            // 2. 상품 조회, 재고 확인/차감, 총액 계산 (동시성 제어 - 비관적 락)
            // productId 기준으로 수량 합산
            Map<Long, Integer> merged = new HashMap<>();
            for (OrderItemRequest item : orderItems) {
                if (item == null) continue;
                if (item.productId() == null) throw new IllegalArgumentException("PRODUCT_ID_REQUIRED");
                if (item.quantity() == null || item.quantity() <= 0) throw new IllegalArgumentException("INVALID_QUANTITY");
                merged.merge(item.productId(), item.quantity(), Integer::sum);
            }

            // 합산된 항목을 productId로 정렬 => 차감 1번씩만 수행
            List<Map.Entry<Long, Integer>> sortedEntries = merged.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();

            BigDecimal paymentAmount = BigDecimal.ZERO;
            Map<Long, Product> productMap = new HashMap<>();

            for (var entry : sortedEntries) {
                Long productId = entry.getKey();
                Integer quantity = entry.getValue();

                Product product = productService.debitTx(productId, quantity);

                BigDecimal itemPrice = product.getPrice().multiply(BigDecimal.valueOf(quantity));
                paymentAmount = paymentAmount.add(itemPrice);

                productMap.put(productId, product);
            }

            // 3. **쿠폰 사용 로직 추가**
            if (userCouponId != null && userCouponId > 0) {
                // A. 사용자 쿠폰 유효성 검사 및 락 획득 (UserCouponRepository에 정의된 findForUpdate 사용)
                UserCoupon userCoupon = couponService.validateAndLockUserCoupon(userCouponId, userId);

                // B. 쿠폰 정보 로드 및 유효 기간 재확인
                Coupon coupon = couponService.getCouponById(userCoupon.getCouponId());
                coupon.validateClaimable(); // 유효 기간 확인

                // C. 할인 금액 계산
                paymentAmount = coupon.calculateDiscountedAmount(paymentAmount);

                // D. 쿠폰 사용 처리 (상태를 USED로 변경)
                userCoupon.use(); // UserCoupon 엔티티 내부에서 상태 변경
                couponService.saveUserCoupon(userCoupon); // DB에 반영
            }

            // 3. 잔액 확인 및 결제 (동시성 제어 - 비관적 락)
            // WalletService.debit()은 내부적으로 SELECT FOR UPDATE를 사용하고, 잔액 부족 시 예외 발생
            walletService.debitTx(userId, paymentAmount);

            // 4. 주문 엔티티 생성 및 저장
            // ⭐ 수정 부분: Order.java의 생성자 시그니처 (userId, totalAmount, itemRequests, productMap)에 맞게 호출
            Order newOrder = new Order(userId, paymentAmount, orderItems, productMap,idempotencyKey);
            Order savedOrder = orderRepository.save(newOrder);

            // 5. 데이터 플랫폼 전송 (트랜잭션 커밋 직전에 실행)
            recordOutboxEvent(savedOrder); // 2. Outbox 기록 메서드 호출

            return savedOrder;
        }catch (DataIntegrityViolationException e) {
            // UNIQUE 제약 위반 → 다른 요청이 먼저 처리함
            return orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }

    public Order createOrderTx(OrderRequest request) {
        return createOrderTx(request.userId(),request.items(),request.userCouponId(),request.idempotencyKey());
    }

    private void recordOutboxEvent(Order order) {
        // Order 객체를 외부 시스템이 요구하는 JSON 형태로 변환
        // 실제로는 별도의 DTO나 ObjectMapper를 사용해 JSON 문자열로 변환합니다.
        String orderJsonPayload = generateOrderPayload(order);

        Outbox outboxEvent = new Outbox("ORDER",
                order.getOrderId().toString(), // 집계 ID로 Order ID 사용
                "ORDER_CREATED",
                orderJsonPayload);

        outboxRepository.save(outboxEvent);
    }

    private String generateOrderPayload(Order order) {

        List<OrderCreatedEventPayload.Item> items =
                order.getOrderItems().stream()
                        .map(oi -> new OrderCreatedEventPayload.Item(
                                oi.getProductId(),
                                oi.getQuantity()
                        )).toList();

        OrderCreatedEventPayload payload = new OrderCreatedEventPayload(
                order.getOrderId(),
                order.getUserId(),
                order.getPaidAmount(),
                items
        );

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ORDER_OUTBOX_PAYLOAD_SERIALIZE_ERROR", e);
        }
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
