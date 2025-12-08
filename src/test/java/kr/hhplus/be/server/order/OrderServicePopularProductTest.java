package kr.hhplus.be.server.order;

import kr.hhplus.be.server.order.DTO.OrderItemRequest;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.product.popularProduct.PopularProduct;
import kr.hhplus.be.server.product.popularProduct.PopularProductRepository;
import kr.hhplus.be.server.wallet.Wallet; // 🌟 추가
import kr.hhplus.be.server.wallet.WalletRepository; // 🌟 추가
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class OrderServicePopularProductTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PopularProductRepository popularProductRepository;

    @Autowired // 🌟 WalletRepository 주입
    private WalletRepository walletRepository;

    // 테스트용 상수
    private static final Long TEST_USER_ID = 100L;
    private static final Long TEST_PRODUCT_ID = 1L;
    private static final BigDecimal TEST_PRICE = BigDecimal.valueOf(5000);
    private static final int INITIAL_STOCK = 1000;
    private static final int INITIAL_SALES_QUANTITY = 10;
    private static final int ORDER_QUANTITY = 5;

    // 🌟 포인트 테스트를 위한 상수 추가
    private static final BigDecimal INITIAL_BALANCE = BigDecimal.valueOf(50000); // 초기 잔액
    private static final BigDecimal USED_POINTER_SINGLE = BigDecimal.valueOf(1000); // 단일 테스트 사용 포인트
    private static final BigDecimal USED_POINTER_CONCURRENT = BigDecimal.valueOf(100); // 동시성 테스트 사용 포인트 (스레드 당)

    @BeforeEach
    @Transactional
    void setUp() {
        productRepository.deleteAll();
        popularProductRepository.deleteAll();
        walletRepository.deleteAll(); // 🌟 Wallet 데이터 정리

        // 1. 상품 등록
        Product product = new Product(TEST_PRODUCT_ID, "Test Product", TEST_PRICE, INITIAL_STOCK);
        productRepository.save(product);

        // 2. 인기 상품 등록
        PopularProduct popularProduct = new PopularProduct(TEST_PRODUCT_ID, INITIAL_SALES_QUANTITY);
        popularProductRepository.save(popularProduct);

        // 3. 🌟 지갑(Wallet) 등록 및 초기 포인트 충전
        Wallet wallet = new Wallet(TEST_USER_ID, INITIAL_BALANCE);
        walletRepository.save(wallet);
    }

    @Test
    @Transactional
    public void 주문_생성_시_인기상품_판매수량_포인트_단일_증가_성공() {
        // given
        var orderItemRequest = new OrderItemRequest(TEST_PRODUCT_ID, ORDER_QUANTITY);
        // 🌟 0이 아닌 사용 포인터 설정
        BigDecimal usedPointer = USED_POINTER_SINGLE;
        int expectedSalesQuantity = INITIAL_SALES_QUANTITY + ORDER_QUANTITY;

        // 🌟 예상 최종 지갑 잔액 계산
        BigDecimal expectedFinalBalance = INITIAL_BALANCE.subtract(usedPointer);

        // when
        Order savedOrder = orderService.createOrder(TEST_USER_ID, usedPointer, List.of(orderItemRequest));

        // then
        // 1. PopularProduct의 업데이트된 판매 수량 검증
        var updatedPopularProduct = popularProductRepository.findByProductId(TEST_PRODUCT_ID)
                .orElseThrow(() -> new AssertionError("PopularProduct not found"));
        assertThat(updatedPopularProduct.getSalesQuantity()).isEqualTo(expectedSalesQuantity);

        // 2. 🌟 Wallet 잔액 검증
        var updatedWallet = walletRepository.findById(TEST_USER_ID)
                .orElseThrow(() -> new AssertionError("Wallet not found"));
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo(expectedFinalBalance);

        assertEquals(OrderStatus.ORDERED, savedOrder.getStatus());
    }
}