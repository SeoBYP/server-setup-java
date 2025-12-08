package kr.hhplus.be.server.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    // Worker가 처리할 PENDING 상태의 이벤트를 조회
    List<Outbox> findTop100ByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus status);
}