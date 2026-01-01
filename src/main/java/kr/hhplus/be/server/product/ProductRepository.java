package kr.hhplus.be.server.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Product w where w.productId = :productId")
    Optional<Product> findForUpdate(@Param("productId") Long productId);

    List<Product> findByProductIdIn(List<Long> productIds);

    Optional<Product> findByName(String name);
}
