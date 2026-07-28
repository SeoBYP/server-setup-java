package kr.hhplus.be.server.coupon;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class CouponAsyncConfig {

    /**
     * 쿠폰 발급 응답을 조립하는 전용 풀.
     *
     * Kafka reply를 받아 CompletableFuture를 완료시키는 스레드는
     * KafkaListener 컨슈머 스레드다. 그 스레드에서 DB 재조회 같은 블로킹 작업을 수행하면
     * 컨슈머의 poll 루프가 지연되어 처리량이 떨어진다.
     *
     * 따라서 응답 조립은 handleAsync(..., 이 executor)로 넘겨
     * 컨슈머 스레드를 즉시 반납한다.
     */
    @Bean(name = "couponReplyExecutor", destroyMethod = "shutdown")
    public ExecutorService couponReplyExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "coupon-reply-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(16, factory);
    }
}
