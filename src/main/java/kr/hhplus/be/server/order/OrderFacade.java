package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.order.DTO.OrderRequest;
import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OrderFacade {

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private OrderService orderService;

    public Order createOrder (Long userId, List<OrderItemRequest> orderItems, Long userCouponId, String idempotencyKey)
    {
        if (userId == null) throw new IllegalArgumentException("USER_ID_REQUIRED");
        if (orderItems == null || orderItems.isEmpty()) throw new IllegalArgumentException("ORDER_ITEMS_EMPTY");

        // ✅ 1) 유저 단위 락 (동일 유저 주문 직렬화)
        String userKey = "lock:order:create:" + userId;
        String userToken = redisLockService.tryLock(userKey, 3000, 5000);
        if (userToken == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        // ✅ 2) 상품 락: productId 합산 + 정렬 (락 순서 고정)
        Map<Long, Integer> merged = new HashMap<>();
        for (OrderItemRequest item : orderItems) {
            if (item == null || item.productId() == null) throw new IllegalArgumentException("PRODUCT_ID_REQUIRED");
            if (item.quantity() == null || item.quantity() <= 0) throw new IllegalArgumentException("INVALID_QUANTITY");
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }

        List<Long> sortedProductIds = merged.keySet().stream()
                .sorted()
                .toList();

        // ✅ 쿠폰 락: 쿠폰 사용 시 UserCoupon 단위로 1개만 잠금
        String couponKey = null;
        String couponToken = null;
        if (userCouponId != null && userCouponId > 0) {
            couponKey = "lock:userCoupon:use:" + userCouponId;
            couponToken = redisLockService.tryLock(couponKey, 3000, 10000);
            if (couponToken == null) {
                redisLockService.unlock(userKey, userToken);
                throw new IllegalStateException("LOCK_ACQUIRE_FAILED:coupon");
            }
        }

        // 3) 상품 락을 모두 획득 (획득한 순서를 기억했다가 역순 해제)
        List<LockHandle> acquiredProductLocks = new ArrayList<>();
        try{
            for (Long productId : sortedProductIds) {
                String productKey = "lock:product:stock:" + productId;
                String productToken = redisLockService.tryLock(productKey, 3000, 10000);
                if (productToken == null) {
                    throw new IllegalStateException("LOCK_ACQUIRE_FAILED:product:" + productId);
                }
                acquiredProductLocks.add(new LockHandle(productKey, productToken));
            }

            // 4) 이제 “주문 생성” 전체를 원자 구간으로 실행
            return orderService.createOrderTx(userId, orderItems, userCouponId, idempotencyKey);
        }finally {
            // 5) 역순 unlock (데드락/락 홀딩 최소화 관점)
            for (int i = acquiredProductLocks.size() - 1; i >= 0; i--) {
                LockHandle h = acquiredProductLocks.get(i);
                redisLockService.unlock(h.key, h.token);
            }

            if (couponKey != null && couponToken != null) {
                redisLockService.unlock(couponKey, couponToken);
            }

            redisLockService.unlock(userKey, userToken);
        }
    }

    public Order createOrder(OrderRequest request) {
        return createOrder(request.userId(),request.items(),request.userCouponId(),request.idempotencyKey());
    }

    private static class LockHandle {
        final String key;
        final String token;
        LockHandle(String key, String token) {
            this.key = key;
            this.token = token;
        }
    }
}
