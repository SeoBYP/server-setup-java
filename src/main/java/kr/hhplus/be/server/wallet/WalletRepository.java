package kr.hhplus.be.server.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long>
{
//    DB Lock
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @Query("select w from Wallet w where w.userId = :userId")
//    Optional<Wallet> findForUpdate(@Param("userId") Long userId);

    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findForUpdate(@Param("userId") Long userId);
}
