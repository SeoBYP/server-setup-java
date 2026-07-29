package kr.hhplus.be.server.coupon;

import jakarta.persistence.EntityManager;
import kr.hhplus.be.server.coupon.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

@Service
public class CouponService {
    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EntityManager entityManager;

    private final DefaultRedisScript<Long> claimScript = new DefaultRedisScript<>();

    public CouponService() {
        claimScript.setResultType(Long.class);
        claimScript.setScriptText("""
            local remainKey = KEYS[1]
            local issuedKey = KEYS[2]
            local userId = ARGV[1]
            
            if redis.call('SISMEMBER', issuedKey, userId) == 1 then
              return -1
            end
            
            local remainStr = redis.call('GET', remainKey)
            local remain = tonumber(remainStr)
            if remain == nil then
              return -2 -- NOT_INITIALIZED (방어)
            end
            if remain <= 0 then
              return 0 -- SOLD_OUT
            end
            
            redis.call('DECR', remainKey)
            redis.call('SADD', issuedKey, userId)
            return 1
        """);
    }

    private void initRemainIfAbsent(Long couponId, long totalQuantity) {
        String remainKey = "coupon:" + couponId + ":remain";
        redisTemplate.opsForValue()
                .setIfAbsent(remainKey, String.valueOf(totalQuantity));
    }

    // --- 발급 경로 왕복 축소 -------------------------------------------------
    // 발급 요청마다 (1) 동일한 쿠폰 행을 DB에서 읽고 (2) 이미 초기화된 Redis 키에
    // SETNX를 다시 걸고 있었다. 부하 테스트에서 컨슈머 스레드가 전부
    // DB 응답 대기(FullReadInputStream.readFully) 상태로 포화된 원인 중 하나다.
    //
    // validateClaimable()은 startsAt/endsAt만 읽고 시각은 호출 시점에 평가하므로
    // 쿠폰 스냅샷을 짧게 캐시해도 만료/개시 판정이 틀어지지 않는다.
    // 잔여 수량의 권위는 Redis(Lua)이므로 캐시된 remainingQuantity는 최초 초기화에만 쓰인다.
    private static final long COUPON_CACHE_TTL_MS = 1_000L;
    private final Map<Long, CachedCoupon> couponCache = new ConcurrentHashMap<>();
    private final Set<Long> remainInitialized = ConcurrentHashMap.newKeySet();

    private record CachedCoupon(Coupon coupon, long loadedAtMs) {}

    private Coupon loadCoupon(Long couponId, boolean forceReload) {
        long now = System.currentTimeMillis();
        if (!forceReload) {
            CachedCoupon cached = couponCache.get(couponId);
            if (cached != null && now - cached.loadedAtMs() < COUPON_CACHE_TTL_MS) {
                return cached.coupon();
            }
        }
        Coupon fresh = couponRepository.findById(couponId).orElseThrow(CouponNotFoundException::new);
        couponCache.put(couponId, new CachedCoupon(fresh, now));
        return fresh;
    }

    /** Redis remain 키 초기화는 쿠폰당 1회면 충분하다. */
    private void ensureRemainInitialized(Long couponId, long totalQuantity) {
        if (remainInitialized.contains(couponId)) return;
        initRemainIfAbsent(couponId, totalQuantity);
        remainInitialized.add(couponId);
    }

    /** Redis 키가 사라진 경우(재시작/시드 초기화) 캐시를 버리고 DB 기준으로 다시 세운다. */
    private void reinitializeRemain(Long couponId) {
        remainInitialized.remove(couponId);
        couponCache.remove(couponId);
        Coupon fresh = loadCoupon(couponId, true);
        ensureRemainInitialized(couponId, fresh.getRemainingQuantity());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateIncrementRemaining(Long couponId) {
        couponRepository.incrementRemaining(couponId);
    }

    @Transactional
    public UserCoupon claimCouponByMessage(String requestId, Long userId, Long couponId) {
        var idempotent = userCouponRepository.findByRequestId(requestId);
        if (idempotent.isPresent()) return idempotent.get();

        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);

        coupon.validateClaimable();

        var existing = userCouponRepository
                .findByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED);
        if (existing.isPresent()) {
            return existing.get();
        }

        int updated = couponRepository.decrementRemainingIfAvailable(couponId);
        if (updated == 0) {
            throw new CouponSoldOutException();
        }

        try {
            return tryInsertUserCoupon(requestId, userId, couponId);
        } catch (DataIntegrityViolationException e) {
            entityManager.clear();
            compensateIncrementRemaining(couponId);
            throw new CouponAlreadyClaimedException();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserCoupon tryInsertUserCoupon(String requestId, Long userId, Long couponId) {
        return userCouponRepository.save(new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED));
    }

    @Transactional
    public UserCoupon claimCouponTx(Long userId, Long couponId, String requestId) {
        var coupon = loadCoupon(couponId, false);
        coupon.validateClaimable();

        // Redis remain 초기값은 DB의 남은 수량 기준. 쿠폰당 1회만 수행한다.
        ensureRemainInitialized(couponId, coupon.getRemainingQuantity());

        String remainKey = "coupon:" + couponId + ":remain";
        String issuedKey = "coupon:" + couponId + ":issued";

        Long r = redisTemplate.execute(
                claimScript,
                List.of(remainKey, issuedKey),
                userId.toString()
        );

        // remainKey가 없으면(재시작/시드 초기화) DB 기준으로 다시 세우고 1회 재시도한다.
        if (r != null && r == -2L) {
            reinitializeRemain(couponId);
            r = redisTemplate.execute(
                    claimScript,
                    List.of(remainKey, issuedKey),
                    userId.toString()
            );
        }

        if (r == null) throw new IllegalStateException("REDIS_EXECUTE_FAILED");

        if (r == -1L) throw new CouponAlreadyClaimedException();
        // ✅ r == 0 은 SOLD_OUT 이므로 "이미 사용됨"이 아니라 "소진"으로 처리
        if (r == 0L) throw new CouponSoldOutException();
        if (r == -2L) throw new IllegalStateException("COUPON_REDIS_NOT_INITIALIZED");

        try {
            UserCoupon newUserCoupon = new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED);
            return userCouponRepository.save(newUserCoupon);
        } catch (DataIntegrityViolationException e) {
            // ✅ DB UNIQUE 충돌이면 Redis에서 선점한 발급 흔적을 롤백
            redisTemplate.opsForSet().remove(issuedKey, userId.toString());
            redisTemplate.opsForValue().increment(remainKey);
            throw new CouponAlreadyClaimedException("DB_CONFLICT");
        }
    }

    @Transactional
    public UserCoupon validateAndLockUserCoupon(Long userCouponId, Long expectedUserId) {
        UserCoupon userCoupon = userCouponRepository.findForUpdate(userCouponId)
                .orElseThrow(UserCouponNotFoundException::new);

        if (!userCoupon.getUserId().equals(expectedUserId)) {
            throw new CouponOwnerMismatchException();
        }

        if (userCoupon.getCouponStatus() != CouponStatus.CLAIMED) {
            throw new CouponAlreadyUsedException();
        }

        return userCoupon;
    }

    @Transactional
    public Coupon getCouponById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);
    }

    @Transactional
    public UserCoupon saveUserCoupon(UserCoupon userCoupon) {
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public UserCoupon useCouponTx(Long userCouponId) {
        var userCoupon = userCouponRepository.findForUpdate(userCouponId).get();
        userCoupon.use();
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public List<UserCoupon> getUserCoupons(Long userId) {
        return userCouponRepository.findAllByUserId(userId);
    }

    /**
     * 발급 응답 조립용 단건 조회.
     * 기존에는 사용자의 쿠폰 목록 전체를 읽어 스트림으로 하나를 골랐다.
     * 발급이 누적될수록 응답당 읽는 행 수가 늘어나므로 PK 조회로 대체한다.
     */
    public Optional<UserCoupon> getUserCoupon(Long userCouponId) {
        return userCouponRepository.findById(userCouponId);
    }
}