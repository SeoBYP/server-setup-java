package kr.hhplus.be.server.outbox.messages;

public record OrderCreatedEventMessage(
        Long eventId,
        String eventType,
        int schemaVersion,
        Long orderId,
        Object payload
) {
}
