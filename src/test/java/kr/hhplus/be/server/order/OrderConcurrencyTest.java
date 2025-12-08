package kr.hhplus.be.server.order;

import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.product.InsufficientStockException;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.product.ProductService;
import kr.hhplus.be.server.wallet.InsufficientBalanceException;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import kr.hhplus.be.server.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderConcurrencyTest {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        walletRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 주문 생성 요청 시 한 번만 성공한다.")
    void 주문_생성_경쟁_테스트() throws Exception {
        // given
        Long userId = 1L;

        List<Product> savedProducts = new ArrayList<Product>();
        savedProducts.add(new Product(1L, "Test1", BigDecimal.valueOf(100), 10));
        savedProducts.add(new Product(2L, "Test2", BigDecimal.valueOf(100), 10));
        productRepository.saveAll(savedProducts);

        Wallet savedWallet = new Wallet(userId, BigDecimal.valueOf(10000));
        walletRepository.save(savedWallet);
        String idempotencyKey = UUID.randomUUID().toString();
        // when
        List<OrderItemRequest> orderItemRequests = new ArrayList<>();
        orderItemRequests.add(new OrderItemRequest(1L, 5));
        orderItemRequests.add(new OrderItemRequest(2L, 5));


        int threadCount = 10; // 동시에 10번 발급 시도
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when : 여러 스레드가 동시에 createOrder 호출
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기

                    orderService.createOrder(userId, orderItemRequests, 0L, idempotencyKey);
                    successCount.incrementAndGet();

                } catch (CouponAlreadyClaimedException e) {
                    // 이미 누가 먼저 발급 받았을 때
                    failCount.incrementAndGet();

                } catch (Exception e) {
                    // 예상치 못한 예외도 실패로 카운트
                    e.printStackTrace();
                    failCount.incrementAndGet();

                } finally {
                    doneLatch.countDown();
                }
            });
        }
        // 모든 스레드를 동시에 출발
        startLatch.countDown();

        // 모든 작업이 끝날 때까지 대기
        doneLatch.await();
        executor.shutdown();

        // then
        // 1) DB에 주문은 1건만 있어야 한다
        var orders = orderRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(userId))
                .toList();
        assertEquals(1, orders.size());

        // 2) 지갑 잔액은 한 번만 차감되었는지
        var wallet = walletRepository.findById(userId).get();
        assertTrue(BigDecimal.valueOf(9000).compareTo(wallet.getBalance()) == 0);

        // 3) 재고도 한 번만 차감되었는지
        var p1 = productRepository.findById(1L).get();
        var p2 = productRepository.findById(2L).get();
        assertEquals(5, p1.getStock());
        assertEquals(5, p2.getStock());
    }
}
