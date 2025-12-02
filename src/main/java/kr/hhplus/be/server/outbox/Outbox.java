package kr.hhplus.be.server.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox")
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType; // 예: "ORDER"

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId; // 예: 주문 ID

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload; // 전송할 주문 정보 (JSON 형태)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status; // PENDING, SENT, FAILED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // OutboxStatus Enum
    public enum OutboxStatus {
        PENDING, SENT, FAILED
    }

    protected Outbox() {}

    public Outbox(String aggregateType, String aggregateId, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }

    // Business Methods
    public void markAsSent() {
        this.status = OutboxStatus.SENT;
    }

    public void markAsFailed() {
        this.status = OutboxStatus.FAILED;
    }
}