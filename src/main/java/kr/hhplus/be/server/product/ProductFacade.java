package kr.hhplus.be.server.product;

import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProductFacade {

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private ProductService productService;

    public Product charge(Long productId, Integer amount){
        String key = "lock:product:stock:" + productId;
        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try{
            return productService.charge(productId, amount);
        }finally {
            redisLockService.unlock(key,token);
        }
    }

    public Product debit(Long productId, Integer amount){
        String key = "lock:product:stock:" + productId;
        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try{
            return productService.debit(productId, amount);
        }finally {
            redisLockService.unlock(key,token);
        }
    }

}
