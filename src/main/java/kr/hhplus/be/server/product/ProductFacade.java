package kr.hhplus.be.server.product;

import kr.hhplus.be.server.product.DTO.CreateProductRequest;
import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Service
public class ProductFacade {

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private ProductService productService;

    public Product charge(Long productId, Integer amount) {
        String key = "lock:product:stock:" + productId;
        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return productService.chargeTx(productId, amount);
        } finally {
            redisLockService.unlock(key, token);
        }
    }

    public Product debit(Long productId, Integer amount) {
        String key = "lock:product:stock:" + productId;
        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return productService.debitTx(productId, amount);
        } finally {
            redisLockService.unlock(key, token);
        }
    }

    public Product createProduct(CreateProductRequest request) {
        String dedup = stableDedupKey(request);
        return createProduct(request, dedup);
    }

    public Product createProduct(CreateProductRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey_required");
        }

        String key = "lock:product:create:" + idempotencyKey;
        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return productService.createProductTx(request);
        } catch (DataIntegrityViolationException e) {
            // ✅ DB UNIQUE가 중복을 막았으니, 기존 상품을 반환(멱등)
            return productService.getByNameTx(request.name());
        } finally {
            redisLockService.unlock(key, token);
        }
    }


    private String stableDedupKey(CreateProductRequest request) {
        // name/price/stock 기준으로 “동일 요청 payload”의 동시성만이라도 제어
        String raw = (request.name() == null ? "" : request.name()) + "|" +
                (request.price() == null ? "" : request.price().toPlainString()) + "|" +
                (request.stock() == null ? "" : request.stock());

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 해시 실패 시에도 최소 동시성 제어가 가능하도록 raw 사용
            return raw;
        }
    }

}
