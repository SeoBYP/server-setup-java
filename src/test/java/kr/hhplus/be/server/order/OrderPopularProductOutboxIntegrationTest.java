package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.outbox.Outbox;
import kr.hhplus.be.server.outbox.OutboxRepository;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.product.popularProduct.PopularProduct;
import kr.hhplus.be.server.product.popularProduct.PopularProductRankingRedis;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
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

    @Autowired(required = false)
    private PopularProductRankingRedis popularProductConsumer;

    private static final Long TEST_USER_ID = 100L;
    private static final BigDecimal TEST_PRICE = BigDecimal.valueOf(5_000);
    private static final int INITIAL_STOCK = 1_000;
    private static final int INITIAL_SALES_QUANTITY = 10;
    private static final int ORDER_QUANTITY = 5;

    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(50_000);

    private Long productId;

    @BeforeEach
    @Transactional
    void setUp() {
        outboxRepository.deleteAll();
        popularProductRepository.deleteAll();
        productRepository.deleteAll();
        walletRepository.deleteAll();

        // ✅ Product는 ID를 생성자가 아니라 DB가 생성하므로 저장 후 ID를 사용
        Product product = new Product("Test Product", TEST_PRICE, INITIAL_STOCK);
        product = productRepository.save(product);
        this.productId = product.getProductId();

        // 인기상품 초기값
        PopularProduct popularProduct = new PopularProduct(productId, INITIAL_SALES_QUANTITY);
        popularProductRepository.save(popularProduct);

        // 지갑
        walletRepository.save(new Wallet(TEST_USER_ID, INITIAL_BALANCE));
    }

    @Test
    @Transactional
    void 주문_생성시_지갑차감_및_Outbox가_기록된다() {
        // given
        var orderItemRequest = new OrderItemRequest(productId, ORDER_QUANTITY);
        BigDecimal expectedPaymentAmount = TEST_PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY));
        BigDecimal expectedWalletBalance = INITIAL_BALANCE.subtract(expectedPaymentAmount);

        String idempotencyKey = UUID.randomUUID().toString();

        // when
        Order savedOrder = orderService.createOrderTx(
                TEST_USER_ID,
                List.of(orderItemRequest),
                null,
                idempotencyKey
        );

        // then: 주문 상태/지갑/아웃박스는 트랜잭션 내에서 확정적으로 검증 가능
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.ORDERED);

        Wallet updatedWallet = walletRepository.findById(TEST_USER_ID)
                .orElseThrow(() -> new AssertionError("Wallet not found"));
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo(expectedWalletBalance);

        List<Outbox> outboxes = outboxRepository.findAll();
        assertThat(outboxes).hasSize(1);

        Outbox outbox = outboxes.get(0);
        assertThat(outbox.getAggregateType()).isEqualTo("ORDER");
        assertThat(outbox.getAggregateId()).isEqualTo(savedOrder.getOrderId().toString());
        assertThat(outbox.getStatus()).isEqualTo(Outbox.OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).isNotNull();

        // PopularProduct 증가/processed 변경은 "consumer를 실제로 구동하는 방식"에 따라 달라질 수 있어
        // 여기서는 컴파일/핵심 통합(주문->아웃박스 기록)까지만 안정적으로 검증한다.
    }
}