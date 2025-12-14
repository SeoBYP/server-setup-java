package kr.hhplus.be.server.wallet;

import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class WalletFacade {
    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private WalletService walletService;

    public void charge(Long userId, BigDecimal amount)
    {
        String key = "lock:wallet:user:" + userId;
        String token = redisLockService.tryLock(key, 3000,5000);
        if (token == null) {
            throw new IllegalStateException("LOCK_ACQUIRE_FAILED");
        }

        try {
            walletService.chargeTx(userId,amount);
        }finally {
            redisLockService.unlock(key, token);
        }
    }

    public void debit(Long userId, BigDecimal amount)
    {
        String key = "lock:wallet:user:" + userId;

        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) {
            throw new IllegalStateException("LOCK_ACQUIRE_FAILED");
        }

        try {
            walletService.debitTx(userId, amount);
        } finally {
            redisLockService.unlock(key, token);
        }
    }
}
