package kr.hhplus.be.server.order;
import jakarta.persistence.LockModeType;
import kr.hhplus.be.server.product.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Order w where w.orderId = :orderId")
    Optional<Order> findForUpdate(@Param("orderId") Long orderId);
}
