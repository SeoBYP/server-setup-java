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
    private WalletFacade walletFacade;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 지갑 충전 요청이 들어와도 모든 요청이 합산되어 최종 잔액이 정확하다")
    void 동시_지갑_충전_정합성_테스트() throws Exception
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

                    walletFacade.charge(userId, chargeAmount);
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

    @Test
    @DisplayName("다수 유저가 동시에 지갑 충전 요청 시 각자 정합성이 유지된다")
    void 다수_유저_지갑_충전_경쟁_테스트() throws Exception {
        // given
        Long[] userIds = {1L, 2L, 3L};
        BigDecimal amount = BigDecimal.valueOf(1000);

        // 1) 각 유저 지갑 0으로 세팅
        for (Long userId : userIds) {
            walletRepository.save(new Wallet(userId, BigDecimal.ZERO));
        }

        int perUserRequests = 10;
        int threadCount = userIds.length * perUserRequests;

        // 2) 스레드/락 준비
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 3) 각 유저에 대해 perUserRequests 만큼 충전 작업 제출
        for (Long userId : userIds) {
            for (int i = 0; i < perUserRequests; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        walletFacade.charge(userId, amount);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
        }

        // 동시에 시작
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then: 각 유저별 최종 잔액 확인
        for (Long userId : userIds) {
            var wallet = walletRepository.findById(userId).get();
            BigDecimal expected = amount.multiply(BigDecimal.valueOf(perUserRequests));
            assertTrue(wallet.getBalance().compareTo(expected) == 0);
        }
        // 그리고 row 수도 유저 수만큼인지 확인 (유저별 row 하나씩)
        assertEquals(userIds.length, walletRepository.findAll().size());
    }

    @Test
    @DisplayName("동시에 지갑 잔액 차감 시도 시 음수 잔액이 발생하지 않고, 성공 횟수는 한도를 넘지 않는다")
    void 동시_잔액_차감_경쟁_테스트() throws Exception {
        // given
        Long userId = 1L;
        BigDecimal initialBalance = BigDecimal.valueOf(1_000);
        BigDecimal withdrawAmount = BigDecimal.valueOf(300);
        int threadCount = 10;

        // 초기 지갑 세팅 ...
        Wallet givenWallet = new Wallet(userId,initialBalance);
        walletRepository.save(givenWallet);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();

        // when - 멀티스레드로 withdraw 호출
        for (int i = 0; i < threadCount; i++)
        {
            executor.submit(() ->{
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기

                    walletFacade.debit(userId, withdrawAmount);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    // 예상치 못한 예외도 실패로 카운트
                    e.printStackTrace();
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
        // 3) 최종 잔액 = initialBalance - (성공횟수 * withdrawAmount)
        var totalUsed = withdrawAmount.multiply(BigDecimal.valueOf(successCount.intValue()));
        var expectedValue = initialBalance.subtract(totalUsed);
        assertTrue(walletService.getBalance(userId).compareTo(expectedValue) == 0);
    }
}
