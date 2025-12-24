package kr.hhplus.be.server.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.outbox.DataPlatformTransmitter;
import kr.hhplus.be.server.outbox.messages.OrderCreatedEventMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class DlqOrderCreatedRetryWorker {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DataPlatformTransmitter transmitter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Value("${app.redis.dlq.order-created:dlq:order-created:v1}")
    private String dlqStreamKey;

    @Value("${app.redis.dlq-group.order-created:cg:dlq:order-created:v1}")
    private String dlqGroup;

    @Value("${app.redis.dlq.max-batch:10}")
    private int maxBatch;

    @Value("${app.redis.dlq.reclaim-idle-seconds:60}")
    private long reclaimIdleSeconds;

    @Value("${app.redis.dlq.max-retry:10}")
    private int maxRetry;

    @Value("${app.redis.dlq.dead-order-created:dead:dlq:order-created:v1}")
    private String deadDlqStreamKey;

    @Value("${app.redis.dlq.retry-key-prefix:dlq:retry:order-created:}")
    private String retryKeyPrefix;

    private final String consumerName = "dlq-worker-" + UUID.randomUUID();

    /**
     * 1) 신규(>) 메시지 처리
     */
    @Scheduled(fixedDelayString = "${app.redis.dlq.drain-interval-ms:2000}")
    void drainNewMessages() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(dlqGroup, consumerName),
                StreamReadOptions.empty()
                        .count(maxBatch)
                        .block(Duration.ofSeconds(2)),
                // ✅ 신규 메시지(>) 강제
                StreamOffset.create(dlqStreamKey, ReadOffset.from(">"))
        );

        if (records == null || records.isEmpty()) return;

        for (MapRecord<String, Object, Object> r : records) {
            handleOne(r.getId().getValue(), r.getValue());
        }
    }

    /**
     * 2) PEL reclaim: XPENDING + XCLAIM(raw)
     */
    @Scheduled(fixedDelayString = "${app.redis.dlq.reclaim-interval-ms:5000}")
    public void reclaimPendingMessages() {
        long minIdleMs = Duration.ofSeconds(reclaimIdleSeconds).toMillis();

        List<PendingEntry> pending = xpending(dlqStreamKey, dlqGroup, maxBatch);
        if (pending.isEmpty()) return;

        List<String> toClaimIds = pending.stream()
                .filter(p -> p.idleTimeMs >= minIdleMs)
                .map(p -> p.id)
                .collect(Collectors.toList());

        if (toClaimIds.isEmpty()) return;

        List<ClaimedEntry> claimed = xclaim(dlqStreamKey, dlqGroup, consumerName, minIdleMs, toClaimIds);
        if (claimed.isEmpty()) return;

        for (ClaimedEntry c : claimed) {
            handleOne(c.id, c.fields);
        }
    }

    /**
     * 단건 처리:
     * - 성공: ACK + DEL + retryKey 삭제
     * - 실패: retryCount 증가, maxRetry 초과면 dead로 이동 후 ACK+DEL
     */
    private void handleOne(String recordId, Map<Object, Object> fields) {
        String retryKey = retryKeyPrefix + recordId;

        // retryCount 증가(처리 시도 횟수)
        long attempt = incrRetry(retryKey);

        try {
            String rawMessageJson = asString(fields.get("message"));
            if (rawMessageJson.isEmpty()) {
                if (attempt >= maxRetry) {
                    moveToDead(recordId, fields, "MISSING_MESSAGE_FIELD", attempt, null);
                    ackAndDelete(recordId);
                    redisTemplate.delete(retryKey);
                }
                return;
            }

            OrderCreatedEventMessage evt =
                    objectMapper.readValue(rawMessageJson, OrderCreatedEventMessage.class);

            String payloadJson = normalizePayloadToJson(evt.payload());

            boolean ok = transmitter.send(evt.eventId(), evt.eventType(), payloadJson);
            if (!ok) {
                if (attempt >= maxRetry) {
                    moveToDead(recordId, fields, "TRANSMIT_FAILED", attempt, null);
                    ackAndDelete(recordId);
                    redisTemplate.delete(retryKey);
                }
                return;
            }

            // ✅ 성공
            ackAndDelete(recordId);
            redisTemplate.delete(retryKey);

        } catch (Exception e) {
            // ✅ 실패 시: maxRetry 넘기기 전에는 "ACK/DEL 하지 않는다" = PEL에 남아야 함
            if (attempt >= maxRetry) {
                moveToDead(recordId, fields, "EXCEPTION", attempt, e);
                ackAndDelete(recordId);
                redisTemplate.delete(retryKey);
            }
        }
    }

    /**
     * XPENDING stream group - min max count
     * => [[id, consumer, idle(ms), delivered], ...]
     */
    private List<PendingEntry> xpending(String stream, String group, int count) {
        byte[] streamB = stream.getBytes(StandardCharsets.UTF_8);
        byte[] groupB = group.getBytes(StandardCharsets.UTF_8);
        byte[] minB = "-".getBytes(StandardCharsets.UTF_8);
        byte[] maxB = "+".getBytes(StandardCharsets.UTF_8);
        byte[] countB = String.valueOf(count).getBytes(StandardCharsets.UTF_8);

        try (RedisConnection conn = connectionFactory.getConnection()) {
            Object raw = conn.execute("XPENDING", streamB, groupB, minB, maxB, countB);
            if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();

            List<PendingEntry> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof List<?> row) || row.size() < 3) continue;
                String id = asString(row.get(0));
                long idle = asLong(row.get(2));
                out.add(new PendingEntry(id, idle));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * XCLAIM stream group consumer minIdle id [id...]
     */
    private List<ClaimedEntry> xclaim(String stream, String group, String consumer, long minIdleMs, List<String> ids) {
        List<byte[]> args = new ArrayList<>();
        args.add(stream.getBytes(StandardCharsets.UTF_8));
        args.add(group.getBytes(StandardCharsets.UTF_8));
        args.add(consumer.getBytes(StandardCharsets.UTF_8));
        args.add(String.valueOf(minIdleMs).getBytes(StandardCharsets.UTF_8));
        for (String id : ids) args.add(id.getBytes(StandardCharsets.UTF_8));

        try (RedisConnection conn = connectionFactory.getConnection()) {
            Object raw = conn.execute("XCLAIM", args.toArray(new byte[0][]));
            if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();

            List<ClaimedEntry> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof List<?> row) || row.size() < 2) continue;
                String id = asString(row.get(0));
                Map<Object, Object> fields = parseFields(row.get(1));
                out.add(new ClaimedEntry(id, fields));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<Object, Object> parseFields(Object fieldsObj) {
        Map<Object, Object> map = new LinkedHashMap<>();
        if (fieldsObj == null) return map;

        if (fieldsObj instanceof List<?> flat) {
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                map.put(asString(flat.get(i)), asString(flat.get(i + 1)));
            }
            return map;
        }

        if (fieldsObj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                map.put(asString(e.getKey()), asString(e.getValue()));
            }
            return map;
        }

        map.put("raw", String.valueOf(fieldsObj));
        return map;
    }

    /**
     * 성공 시 처리: XACK + XDEL
     */
    private void ackAndDelete(String recordId) {
        xack(dlqStreamKey, dlqGroup, recordId);
        xdel(dlqStreamKey, recordId);
    }

    private void xack(String stream, String group, String recordId) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            conn.execute("XACK",
                    stream.getBytes(StandardCharsets.UTF_8),
                    group.getBytes(StandardCharsets.UTF_8),
                    recordId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void xdel(String stream, String recordId) {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            conn.execute("XDEL",
                    stream.getBytes(StandardCharsets.UTF_8),
                    recordId.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /**
     * maxRetry 초과 시 dead stream으로 이동
     */
    private void moveToDead(String recordId, Map<Object, Object> fields, String reason, long attempt, Exception e) {
        Map<String, String> dead = new LinkedHashMap<>();

        for (Map.Entry<Object, Object> entry : fields.entrySet()) {
            dead.put(String.valueOf(entry.getKey()), asString(entry.getValue()));
        }

        dead.put("deadReason", reason);
        dead.put("deadMovedAt", Instant.now().toString());
        dead.put("attempt", String.valueOf(attempt));
        dead.put("sourceRecordId", recordId);
        dead.put("lastError", e == null ? "" : safe(e.getMessage()));

        redisTemplate.opsForStream().add(deadDlqStreamKey, dead);
    }

    private long incrRetry(String retryKey) {
        Long v = redisTemplate.opsForValue().increment(retryKey);
        redisTemplate.expire(retryKey, Duration.ofDays(7));
        return v == null ? 1L : v;
    }

    private String normalizePayloadToJson(Object payload) throws Exception {
        if (payload == null) return "null";
        if (payload instanceof String s) return s;
        return objectMapper.writeValueAsString(payload);
    }

    private String asString(Object o) {
        if (o == null) return "";
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return String.valueOf(o);
    }

    private long asLong(Object o) {
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

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    private static class PendingEntry {
        final String id;
        final long idleTimeMs;
        PendingEntry(String id, long idleTimeMs) {
            this.id = id;
            this.idleTimeMs = idleTimeMs;
        }
    }

    private static class ClaimedEntry {
        final String id;
        final Map<Object, Object> fields;
        ClaimedEntry(String id, Map<Object, Object> fields) {
            this.id = id;
            this.fields = fields;
        }
    }

    /**
     * ✅ 테스트/수동 실행용 엔트리포인트
     * - 여기서는 "신규(>) 처리 1회"만 수행한다.
     * - reclaim까지 같이 하려면 drainDlqWithReclaim()을 사용한다.
     */
    public void drainDlq() {
        drainNewMessages();
    }

    /**
     * 필요 시 수동으로 reclaim까지 수행하고 싶을 때
     */
    public void drainDlqWithReclaim() {
        drainNewMessages();
        reclaimPendingMessages();
    }
}
