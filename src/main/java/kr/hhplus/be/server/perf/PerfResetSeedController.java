// language: java
package kr.hhplus.be.server.perf;

import kr.hhplus.be.server.coupon.Coupon;
import kr.hhplus.be.server.coupon.CouponRepository;
import kr.hhplus.be.server.coupon.CouponType;
import kr.hhplus.be.server.product.Product;
import kr.hhplus.be.server.product.ProductRepository;
import kr.hhplus.be.server.wallet.Wallet;
import kr.hhplus.be.server.wallet.WalletRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Profile({"local","perf"})
@RestController
@RequestMapping("/internal/perf")
public class PerfResetSeedController {

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    private final ProductRepository productRepository;
    private final WalletRepository walletRepository;
    private final CouponRepository couponRepository;

    public PerfResetSeedController(
            JdbcTemplate jdbcTemplate,
            RedisConnectionFactory redisConnectionFactory,
            ProductRepository productRepository,
            WalletRepository walletRepository,
            CouponRepository couponRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
        this.productRepository = productRepository;
        this.walletRepository = walletRepository;
        this.couponRepository = couponRepository;
    }

    public record ResetSeedResponse(
            Long hotCouponId,
            int productCount,
            int hotProductCount,
            int walletUserCount,
            BigDecimal walletInitialBalance
    ) {}

    @PostMapping("/reset-seed")
    public ResetSeedResponse resetAndSeed(
            @RequestParam(defaultValue = "1000") int productCount,
            @RequestParam(defaultValue = "20") int hotProductCount,
            @RequestParam(defaultValue = "10000") int walletUserCount,
            @RequestParam(defaultValue = "1000") int hotCouponQuantity,
            // ✅ 안정적 부하테스트용: 모든 유저에게 동일한 초기 잔액을 넣음(멱등 seed)
            @RequestParam(defaultValue = "1000000") BigDecimal walletInitialBalance
    ) {
        if (walletInitialBalance.signum() < 0) {
            throw new IllegalArgumentException("walletInitialBalance>=0");
        }

        resetMysql();
        resetRedis();

        seedProducts(productCount, hotProductCount);
        seedWallets(walletUserCount, walletInitialBalance);

        Coupon hotCoupon = couponRepository.save(
                new Coupon("PERF_COUPON", CouponType.FIXED, BigDecimal.valueOf(1000), hotCouponQuantity)
        );

        return new ResetSeedResponse(
                hotCoupon.getCouponId(),
                productCount,
                hotProductCount,
                walletUserCount,
                walletInitialBalance
        );
    }

    private void resetMysql() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=0");

        truncate("order_item");
        truncate("orders");

        truncate("outbox");
        truncate("popular_products");

        truncate("user_coupons");
        truncate("coupons");

        truncate("wallets");
        truncate("products");

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private void truncate(String table) {
        jdbcTemplate.execute("TRUNCATE TABLE " + table);
    }

    private void resetRedis() {
        var conn = redisConnectionFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    private void seedProducts(int productCount, int hotProductCount) {
        int hot = Math.max(0, Math.min(hotProductCount, productCount));

        List<Product> batch = new ArrayList<>(productCount);
        for (int i = 1; i <= productCount; i++) {
            int stock = (i <= hot) ? 200 : 20000;
            BigDecimal price = BigDecimal.valueOf(1000 + (i % 200) * 100L);
            batch.add(new Product("PERF_PRODUCT_" + i, price, stock));
        }
        productRepository.saveAll(batch);
    }

    private void seedWallets(int walletUserCount, BigDecimal initialBalance) {
        List<Wallet> batch = new ArrayList<>(walletUserCount);
        for (long userId = 1; userId <= walletUserCount; userId++) {
            // ✅ 전원 충분한 잔액을 보장해서 INSUFFICIENT_BALANCE 제거
            batch.add(new Wallet(userId, initialBalance));
        }
        walletRepository.saveAll(batch);
    }
}