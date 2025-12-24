package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.product.popularProduct.PopularProduct;
import kr.hhplus.be.server.product.popularProduct.PopularProductRankingRedis;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderPopularProductOutboxIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PopularProductRepository popularProductRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PopularProductRankingRedis popularProductConsumer;

    // 테스트용 상수
    private static final Long TEST_USER_ID = 100L;
    private static final Long TEST_PRODUCT_ID = 1L;
    private static final BigDecimal TEST_PRICE = BigDecimal.valueOf(5_000);
    private static final int INITIAL_STOCK = 1_000;
    private static final int INITIAL_SALES_QUANTITY = 10;
    private static final int ORDER_QUANTITY = 5;

    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(50_000); // 초기 잔액

    @BeforeEach
    @Transactional
    void setUp() {
        // 테스트마다 깨끗하게 초기화
        outboxRepository.deleteAll();
        popularProductRepository.deleteAll();
        productRepository.deleteAll();
        walletRepository.deleteAll();

        // 1. 상품 등록
        Product product = new Product(TEST_PRODUCT_ID, "Test Product", TEST_PRICE, INITIAL_STOCK);
        productRepository.save(product);

        // 2. 인기 상품 초기값 등록
        PopularProduct popularProduct = new PopularProduct(TEST_PRODUCT_ID, INITIAL_SALES_QUANTITY);
        popularProductRepository.save(popularProduct);

        // 3. 지갑(Wallet) 등록 및 초기 잔액 셋업
        Wallet wallet = new Wallet(TEST_USER_ID, INITIAL_BALANCE);
        walletRepository.save(wallet);
    }

    @Test
    @Transactional
    void 주문_생성_후_컨슈머_실행시_인기상품_판매수량_증가_및_아웃박스_processed_갱신_성공() {
        // given
        var orderItemRequest = new OrderItemRequest(TEST_PRODUCT_ID, ORDER_QUANTITY);
        BigDecimal expectedPaymentAmount = TEST_PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY));
        BigDecimal expectedWalletBalance = INITIAL_BALANCE.subtract(expectedPaymentAmount);
        int expectedSalesQuantity = INITIAL_SALES_QUANTITY + ORDER_QUANTITY;

        String idempotencyKey = UUID.randomUUID().toString();

        // when - 1) 주문 생성
        Order savedOrder = orderService.createOrderTx(
                TEST_USER_ID,
                List.of(orderItemRequest),
                null,
                idempotencyKey
        );

        // then - 1) 주문 상태 및 Wallet, Outbox 검증
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.ORDERED);

        // Wallet 잔액 감소 확인
        Wallet updatedWallet = walletRepository.findById(TEST_USER_ID)
                .orElseThrow(() -> new AssertionError("Wallet not found"));
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo(expectedWalletBalance);

        // Outbox에 ORDER 이벤트가 1건 생성되었는지 확인
        List<Outbox> outboxes = outboxRepository.findAll();
        assertThat(outboxes).hasSize(1);

        Outbox outbox = outboxes.get(0);
        assertThat(outbox.getAggregateType()).isEqualTo("ORDER");
        assertThat(outbox.getAggregateId()).isEqualTo(savedOrder.getOrderId().toString());
        assertThat(outbox.getStatus()).isEqualTo(Outbox.OutboxStatus.PENDING);
        assertThat(outbox.isProcessed()).isFalse();

        // then - 2) PopularProduct, Outbox.processed 플래그 검증
        PopularProduct updatedPopularProduct = popularProductRepository.findByProductId(TEST_PRODUCT_ID)
                .orElseThrow(() -> new AssertionError("PopularProduct not found"));
        assertThat(updatedPopularProduct.getSalesQuantity()).isEqualTo(expectedSalesQuantity);

        Outbox processedOutbox = outboxRepository.findById(outbox.getId())
                .orElseThrow(() -> new AssertionError("Outbox not found after consumer"));
        assertThat(processedOutbox.isProcessed()).isTrue();
        // 실패하지 않았으므로 status는 여전히 PENDING(또는 별도 정책이 있다면 거기에 맞춰 변경 가능)
        assertThat(processedOutbox.getStatus()).isEqualTo(Outbox.OutboxStatus.PENDING);
    }
}
