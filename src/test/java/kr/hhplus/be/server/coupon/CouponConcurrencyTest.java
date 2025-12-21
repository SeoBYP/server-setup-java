package kr.hhplus.be.server.coupon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        userCouponRepository.deleteAll();
        couponRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushDb();
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

                    couponFacade.claimCoupon(userId, coupon.getCouponId());
                    successCount.incrementAndGet();

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

    @Test
    @DisplayName("여러 유저가 동시에 같은 쿠폰을 발급 요청해도 한 명만 성공한다")
    void 다수_유저_쿠폰_발급_경쟁_테스트() throws Exception {
        // given
        int userCount = 10;

        Coupon coupon = new Coupon(
                "GLOBAL_ONCE",
                CouponType.FIXED,
                BigDecimal.valueOf(1000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now()
        );
        couponRepository.save(coupon);

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when: 서로 다른 userId들이 동시에 같은 couponId로 발급 요청
        for (int i = 0; i < userCount; i++)
        {
            final Long userId = (long)(i + 1);
            executor.submit(() ->{
                try
                {
                    startLatch.await();
                    couponFacade.claimCoupon(userId,coupon.getCouponId());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        // 1) 논리적으로 성공 1번, 실패 9번이어야 한다
        assertEquals(1, successCount.get());
        assertEquals(userCount - 1, failCount.get());

        // 2) 실제 DB에도 이 쿠폰에 대한 UserCoupon은 1건만 있어야 한다
        List<UserCoupon> userCoupons = userCouponRepository.findAll().stream()
                .filter(uc -> uc.getCouponId().equals(coupon.getCouponId()))
                .toList();
        assertEquals(1, userCoupons.size());
    }

    @Test
    @DisplayName("동시에 같은 쿠폰 사용 요청 시 한 번만 성공한다")
    void 동시_쿠폰_사용_경쟁_테스트() throws Exception {
        // given
        Long userId = 1L;

        Coupon coupon = new Coupon(
                "USE_CONC_TEST",
                CouponType.FIXED,
                BigDecimal.valueOf(1000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now()
        );
        couponRepository.save(coupon);

        // 먼저 발급 1건 생성 (CLAIMED 상태)
        UserCoupon issued = couponService.claimCouponTx(userId, coupon.getCouponId());
        Long userCouponId = issued.getUserCouponId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    couponFacade.useCoupon(userCouponId); // 분산락 적용 지점
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 락 실패/기타 예외도 실패로 카운트
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        assertEquals(1, successCount.get());
        assertEquals(threadCount - 1, failCount.get());

        // 최종 상태가 USED인지 확인 (enum명이 USED가 아니라면 실제 값으로 변경)
        UserCoupon reloaded = userCouponRepository.findById(userCouponId).orElseThrow();
        assertEquals(CouponStatus.USED, reloaded.getCouponStatus());
    }


}
