package kr.hhplus.be.server.wallet;

import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class WalletConcurrencyTest {
    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 지갑 충전 요청 시 한 번만 성공한다")
    void 동시_지갑_충전_경쟁_테스트() throws Exception
    {
        // given
        Long userId = 1L;
        BigDecimal chargeAmount = BigDecimal.valueOf(1000L);

        Wallet givenWallet = new Wallet(userId,BigDecimal.ZERO);
        walletRepository.save(givenWallet);

        int threadCount = 10; // 동시에 10번 발급 시도
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when : 여러 스레드가 동시에 charge 호출
        for (int i = 0; i < threadCount; i++)
        {
            executor.submit(() ->{
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기

                    walletService.charge(userId, chargeAmount);
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
        var wallets = walletRepository.findAll().stream()
                .filter(w -> w.getUserId().equals(userId))
                .toList();
        assertEquals(1, wallets.size());

        // 지갑 충전 금액 확인
        var expectedValue = chargeAmount.multiply(BigDecimal.valueOf(threadCount));
        assertTrue(walletService.getBalance(userId).compareTo(expectedValue) == 0);
    }
}
