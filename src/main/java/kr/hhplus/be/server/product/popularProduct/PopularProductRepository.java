package kr.hhplus.be.server.product.popularProduct;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PopularProductRepository extends JpaRepository<PopularProduct, Long> {

    // 기존 메서드 (id로 조회):
    @Query("select w from PopularProduct w where w.id = :id")
    Optional<PopularProduct> findForUpdate(@Param("id") Long id);

    Optional<PopularProduct> findByProductId(Long productId);

    @Query("select w from PopularProduct w where w.productId = :productId")
    Optional<PopularProduct> findForUpdateByProductId(@Param("productId") Long productId);

    @Query(value = "SELECT pp.product_id FROM popular_products pp ORDER BY pp.sales_quantity DESC LIMIT 5", nativeQuery = true)
    List<Long> findTop5ProductIds();
}
