package kr.hhplus.be.server.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.events.DlqOrderCreatedRetryWorker;
import kr.hhplus.be.server.events.consumer.OrderCreatedEventSubscriber;
import kr.hhplus.be.server.outbox.DataPlatformTransmitter;
import kr.hhplus.be.server.outbox.messages.OrderCreatedEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OrderCreatedEventDlqFlowIT.TestBeans.class)
class OrderCreatedEventDlqFlowIT {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.redis.dlq.order-created", () -> "dlq:order-created:v1");
        r.add("app.redis.dlq-group.order-created", () -> "cg:dlq:order-created:v1");
        r.add("app.redis.dlq.dead-order-created", () -> "dead:dlq:order-created:v1");

        // ✅ 스케줄러 간섭 차단
        r.add("spring.task.scheduling.enabled", () -> "false");
    }

    @Autowired RedisConnectionFactory connectionFactory;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper objectMapper;

    @Autowired OrderCreatedEventSubscriber subscriber;
    @Autowired DlqOrderCreatedRetryWorker dlqWorker;

    @Autowired DataPlatformTransmitter transmitter;
    private StubTransmitter stub() { return (StubTransmitter) transmitter; }

    private final String dlqKey = "dlq:order-created:v1";
    private final String dlqGroup = "cg:dlq:order-created:v1";

    @BeforeEach
    void setUp() {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            Object ping = conn.execute("PING");
            Object info = conn.execute("INFO", "server".getBytes(StandardCharsets.UTF_8));
            System.out.println("PING=" + (ping instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : ping));
            System.out.println("INFO(server)=" + (info instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : info));
        }

        flushAll();
        ensureStreamAndGroupMkstream(dlqKey, dlqGroup);
        stub().reset();
    }

    /**
     * 1) transmitter 예외 발생 -> subscriber가 DLQ Stream에 적재
     */
    @Test
    void transmitFails_thenSubscriberPushesToDlqStream() throws Exception {
        stub().setAlwaysFail(true);

        OrderCreatedEventMessage msg = new OrderCreatedEventMessage(
                1L, "ORDER_CREATED", 1, 100L,
                "{\"orderId\":100,\"items\":[{\"productId\":1,\"qty\":2}]}"
        );
        String json = objectMapper.writeValueAsString(msg);

        subscriber.onMessage(
                new DefaultMessage(
                        "local.order.order-created.v1".getBytes(StandardCharsets.UTF_8),
                        json.getBytes(StandardCharsets.UTF_8)
                ),
                null
        );

        assertThat(xlen(dlqKey)).isEqualTo(1L);

        Map<String, String> last = readLastEntryAsStringMap(dlqKey);
        assertThat(last.get("reason")).isEqualTo("TRANSMIT_ERROR");
        assertThat(last.get("message")).contains("\"eventId\":1");
    }

    /**
     * 2) DLQ 재처리 성공 -> ACK + DEL -> stream 비워짐
     */
    @Test
    void dlqRetrySucceeds_thenWorkerAckAndDeletesEntry() throws Exception {
        stub().setAlwaysFail(true);
        pushDlqBySubscriber(2L, 200L);
        assertThat(xlen(dlqKey)).isEqualTo(1L);

        stub().setAlwaysFail(false);

        dlqWorker.drainDlq();

        assertThat(xlen(dlqKey)).isEqualTo(0L);
        assertThat(stub().callCount.get()).isGreaterThanOrEqualTo(1);
    }

    private void pushDlqBySubscriber(long eventId, long orderId) throws Exception {
        OrderCreatedEventMessage msg = new OrderCreatedEventMessage(
                eventId, "ORDER_CREATED", 1, orderId,
                "{\"orderId\":" + orderId + "}"
        );
        String json = objectMapper.writeValueAsString(msg);

        subscriber.onMessage(
                new DefaultMessage(
                        "local.order.order-created.v1".getBytes(StandardCharsets.UTF_8),
                        json.getBytes(StandardCharsets.UTF_8)
                ),
                null
        );
    }

    // -------------------------
    // Test Beans
    // -------------------------
    @TestConfiguration
    static class TestBeans {

        private final StubTransmitter stub = new StubTransmitter();

        @Bean
        public OrderCreatedEventSubscriber orderCreatedEventSubscriber() {
            return new OrderCreatedEventSubscriber();
        }

        @Bean
        @Primary
        public DataPlatformTransmitter dataPlatformTransmitter() {
            return stub;
        }
    }

    static class StubTransmitter extends DataPlatformTransmitter {
        private volatile boolean alwaysFail = false;
        final AtomicInteger callCount = new AtomicInteger(0);

        void setAlwaysFail(boolean v) { this.alwaysFail = v; }
        void reset() { this.alwaysFail = false; callCount.set(0); }

        @Override
        public boolean send(Long outboxId, String aggregateType, String payload) {
            callCount.incrementAndGet();
            if (alwaysFail) throw new RuntimeException("forced fail(send)");
            return true;
        }
    }

    // -------------------------
    // Redis helpers
    // -------------------------
    private void flushAll() {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            conn.execute("FLUSHALL");
        }
    }

    private void ensureStreamAndGroupMkstream(String stream, String group) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            conn.execute("XGROUP",
                    "CREATE".getBytes(StandardCharsets.UTF_8),
                    stream.getBytes(StandardCharsets.UTF_8),
                    group.getBytes(StandardCharsets.UTF_8),
                    "0-0".getBytes(StandardCharsets.UTF_8),
                    "MKSTREAM".getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ignored) {
            // BUSYGROUP이면 이미 존재 -> 무시
        }
    }

    private long xlen(String stream) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            Object raw = conn.execute("XLEN", stream.getBytes(StandardCharsets.UTF_8));
            return raw == null ? 0L : Long.parseLong(raw.toString());
        }
    }

    private Map<String, String> readLastEntryAsStringMap(String stream) {
        List<MapRecord<String, Object, Object>> recs =
                redisTemplate.opsForStream().reverseRange(stream, Range.unbounded(), Limit.limit().count(1));

        assertThat(recs).isNotNull();
        assertThat(recs).isNotEmpty();

        Map<Object, Object> v = recs.get(0).getValue();
        return Map.of(
                "reason", v.get("reason") == null ? "" : String.valueOf(v.get("reason")),
                "message", v.get("message") == null ? "" : String.valueOf(v.get("message"))
        );
    }

    /**
     * ✅ 가장 견고한 pending 확인 방법:
     * XINFO GROUPS <stream> 결과에서 group의 pending 값을 읽는다.
     */
    private long pendingCountByXinfoGroups(String stream, String group) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            Object raw = conn.execute("XINFO",
                    "GROUPS".getBytes(StandardCharsets.UTF_8),
                    stream.getBytes(StandardCharsets.UTF_8));

            if (!(raw instanceof List<?> groups) || groups.isEmpty()) return 0L;

            for (Object g : groups) {
                // 각 group은 [k1,v1,k2,v2,...] 형태(List)로 오는 경우가 많음
                Map<String, Object> m = toKvMap(g);
                String name = String.valueOf(m.getOrDefault("name", ""));
                if (!group.equals(name)) continue;

                Object pendingObj = m.get("pending");
                return parseLong(pendingObj);
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> toKvMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof List<?> flat) {
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                out.put(String.valueOf(flat.get(i)), flat.get(i + 1));
            }
        }
        return out;
    }

    private long parseLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i.longValue();
        if (o instanceof byte[] b) {
            try { return Long.parseLong(new String(b, StandardCharsets.UTF_8)); }
            catch (Exception ignored) { return 0L; }
        }
        try { return Long.parseLong(String.valueOf(o)); }
        catch (Exception ignored) { return 0L; }
    }
}
