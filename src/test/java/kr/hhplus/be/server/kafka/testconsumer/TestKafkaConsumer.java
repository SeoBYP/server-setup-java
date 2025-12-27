package kr.hhplus.be.server.kafka.testconsumer;

import org.springframework.kafka.annotation.KafkaListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TestKafkaConsumer {

    private final AtomicReference<String> expected = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    public void reset(String expectedPayload) {
        expected.set(expectedPayload);
        received.set(null);
        latch = new CountDownLatch(1);
    }

    public boolean isReceived() {
        try {
            // listener 쓰레드에서 들어오므로, 테스트는 latch로 기다린다
            boolean ok = latch.await(1, TimeUnit.SECONDS);
            return ok && expected.get().equals(received.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @KafkaListener(topics = "order-created")
    public void onMessage(String payload) {
        received.set(payload);
        latch.countDown();
    }
}