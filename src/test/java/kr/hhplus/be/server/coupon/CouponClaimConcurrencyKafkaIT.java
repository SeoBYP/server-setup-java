package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.dto.ClaimRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestPropertySource(properties = {
        "server.port=18080",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",

        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.kafka.consumer.group-id=coupon-claim-it",
        "spring.kafka.consumer.auto-offset-reset=earliest",

        "app.kafka.topics.coupon-claim-requested=coupon-claim-requested.v1",
        "app.kafka.topics.coupon-claim-replied=coupon-claim-replied.v1",

        "app.kafka.consumers.coupon-claim.concurrency=3",
        "app.kafka.consumers.coupon-reply.concurrency=1",

        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.connection-timeout=10000"

})
class CouponClaimConcurrencyKafkaIT {

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    UserCouponRepository userCouponRepository;

    private final RestTemplate restTemplate;

    CouponClaimConcurrencyKafkaIT() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(f);
    }


    @BeforeEach
    void setUp() {
        userCouponRepository.deleteAll();
        couponRepository.deleteAll();
    }

    @Test
    @Timeout(60)
    @DisplayName("Kafka 동기 대기 기반: 100장 선착순 쿠폰에 200명이 동시에 요청하면 성공=100, remaining=0")
    void 선착순_100장_동시발급_테스트() throws Exception {
        // given: 쿠폰 100장
        int quantity = 100;
        Coupon coupon = new Coupon("C-100", CouponType.FIXED, BigDecimal.valueOf(1000), quantity);
        couponRepository.save(coupon);

        Long couponId = coupon.getCouponId();

        int totalRequests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalRequests);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> failReasons = new ConcurrentHashMap<>();

        for (long userId = 1; userId <= totalRequests; userId++) {
            final long uid = userId;
            pool.submit(() -> {
                try {
                    start.await();

                    String url = "http://localhost:18080/api/coupons/claim/" + couponId;

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);

                    // ClaimRequest는 (couponId, userId, reqeustId) 3개 인자 필요
                    ClaimRequest body = new ClaimRequest(couponId, uid, "test-request-" + uid);

                    HttpEntity<ClaimRequest> req = new HttpEntity<>(body, headers);

                    ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, req, String.class);

                    if (resp.getStatusCode().is2xxSuccessful()) {
                        success.incrementAndGet();
                    } else {
                        fail.incrementAndGet();
                    }
                } catch (Exception e) {
                    String key = e.getClass().getSimpleName();
                    failReasons.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    fail.incrementAndGet();

                } finally {
                    done.countDown();
                }
            });
        }
        // done.await() 이후에 한 번 출력(디버깅용)
        System.out.println("failReasons=" + failReasons);

        start.countDown();
        done.await();
        pool.shutdown();

        // then
        assertThat(success.get()).isEqualTo(quantity);
        assertThat(fail.get()).isEqualTo(totalRequests - quantity);

        assertThat(userCouponRepository.count()).isEqualTo(quantity);

        Coupon updated = couponRepository.findById(couponId).orElseThrow();
        assertThat(updated.getRemainingQuantity()).isEqualTo(0);
        assertThat(updated.getTotalQuantity()).isEqualTo(quantity);
    }
}