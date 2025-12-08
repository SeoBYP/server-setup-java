package kr.hhplus.be.server.coupon;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class CouponConcurrencyTest {
    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponService couponService;

    @BeforeEach
    void setUp() {
        userCouponRepository.deleteAll();
        couponRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 쿠폰 발급 요청 시 한 번만 성공한다")
    void 동시_쿠폰_발급_경쟁_테스트() throws Exception
    {
        // given
        Long userId = 1L;

        Coupon coupon = new Coupon(
                "CONC_TEST",
                CouponType.FIXED,
                BigDecimal.valueOf(1000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now()
        );
        couponRepository.save(coupon);

        int threadCount = 10; // 동시에 10번 발급 시도
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when : 여러 스레드가 동시에 claimCoupon 호출
        for (int i = 0; i < threadCount; i++)
        {
            executor.submit(() ->{
                try {
                    startLatch.await(); // 모두 준비될 때까지 대기

                    couponService.claimCoupon(userId, coupon.getCouponId());
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
        // 1) 논리적으로 성공 1번, 실패 9번 이어야 한다.
        assertEquals(1, successCount.get());
        assertEquals(threadCount - 1, failCount.get());

        // 2) 실제 DB에도 발급된 UserCoupon 은 1건만 있어야 한다
        List<UserCoupon> userCoupons = userCouponRepository.findAll().stream()
                .filter(uc -> uc.getUserId().equals(userId)
                        && uc.getCouponId().equals(coupon.getCouponId()))
                .toList();

        assertEquals(1, userCoupons.size());
    }
}
