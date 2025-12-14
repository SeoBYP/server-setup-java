# 분산락과 캐싱 전략 적용 보고서 (Distributed Lock / Cache)

본 문서는 e-커머스 주문 서비스 시나리오에서 **(1) Redis 기반 분산락(필수)**과 **(2) Redis 캐시(선택)**를 어떤 범위/키로 설계·적용했는지, 그리고 **DB 트랜잭션과 혼용 시 주의점** 및 **통합 테스트 결과**를 정리합니다.

---

## 1. Distributed Lock (필수)

### 1.1 목표

* **다중 인스턴스 환경**에서 동일 리소스(지갑/재고/쿠폰/주문)에 대한 동시 접근을 제어하여 경쟁 구간을 줄입니다.
* 최종 정합성은 **DB 트랜잭션(특히 `SELECT ... FOR UPDATE`)과 제약조건**이 보장하며, 분산락은 “진입 제어”로 충돌/실패를 최소화합니다.

---

### 1.2 락 키 설계 (Key)

| 도메인     | 기능           | 락 키                                  | 잠금 단위        | 목적                           |
| ------- | ------------ | ------------------------------------ | ------------ | ---------------------------- |
| Wallet  | 충전/차감        | `lock:wallet:user:{userId}`          | userId       | 동일 유저 지갑 변경을 직렬화             |
| Product | 재고 변경(차감/충전) | `lock:product:stock:{productId}`     | productId    | 동일 상품 재고 경쟁 구간 직렬화           |
| Coupon  | 발급(선착순/중복방지) | `lock:coupon:claim:{couponId}`       | couponId     | 동일 쿠폰 발급 경쟁 제어               |
| Coupon  | 사용           | `lock:userCoupon:use:{userCouponId}` | userCouponId | 동일 쿠폰의 동시 사용 방지              |
| Order   | 주문 생성        | `lock:order:create:{userId}`         | userId       | 동일 유저 주문 생성 직렬화(중복 결제/차감 방지) |

> 멀티 상품 주문은 여러 `productId` 락을 함께 획득합니다. 이때 **획득 순서를 고정하지 않으면 데드락 위험**이 있습니다.

---

### 1.3 락 범위(Scope) 및 획득 순서(Ordering)

#### 주문 생성(핵심) — `OrderFacade`에서 “한 번에” 잠금

* 범위: **주문 생성 전체(= `OrderService.createOrderTx(...)` 호출 전후)**
* 이유: 서비스 내부에서 재고/지갑/쿠폰을 각각 분산락으로 개별 획득하면, **락 해제 시점과 DB 트랜잭션 커밋 시점이 어긋나** 원자 구간이 깨질 수 있습니다.

**획득 순서(고정)**

1. `lock:order:create:{userId}` (유저 단위 주문 직렬화)
2. `orderItems`를 **productId 기준 합산 + 정렬**
3. 정렬된 순서대로 `lock:product:stock:{productId}`를 **전부 획득**
4. (쿠폰 사용 시) `lock:userCoupon:use:{userCouponId}` 획득
5. `orderService.createOrderTx(...)` 실행
6. unlock은 **역순** 수행

**효과**

* 멀티 상품 주문에서 상품 락 획득 순서가 고정되어 **교착 위험 감소**
* 주문 생성 전체가 단일 원자 구간으로 실행되어 **중간 상태 노출 최소화**

---

### 1.4 DB Tx와 분산락 혼용 시 주의점

1. **분산락은 최종 정합성 보장이 아니다**

* 네트워크/TTL 만료/프로세스 장애 등으로 락이 완벽하지 않을 수 있습니다.
* 최종 보루는 **DB 트랜잭션(락) + 제약조건(UNIQUE/CHECK/FK)** 이어야 합니다.

2. **TTL(lease time)과 트랜잭션 실행시간**

* 트랜잭션이 TTL보다 길어지면 락이 먼저 풀려 동시 진입이 발생할 수 있습니다.
* 과제에서는 충분한 TTL을 두고, 운영에서는 Watchdog(연장) 또는 DB 기반 보강이 필요합니다.

3. **멀티 락 획득 시 순서 고정**

* 서로 다른 요청이 서로 다른 순서로 락을 잡으면 데드락 가능성이 증가합니다.
* 따라서 **productId 정렬 후 락 획득**이 필수입니다.

4. **락 범위 과도 확장 주의**

* 락이 너무 넓으면 처리량이 급감합니다.
* 본 과제에서는 “유저 단위 주문 직렬화 + 필요한 상품만 잠금”으로 타협점을 선택했습니다.

---

### 1.5 구현 요약

#### `OrderFacade`

* 유저 단위 락(`lock:order:create:{userId}`) 획득
* 상품 락은 `orderItems`를 **합산/정렬** 후 `lock:product:stock:{productId}`를 **전부 획득**
* 쿠폰 사용 시 `lock:userCoupon:use:{userCouponId}` 추가 획득
* unlock은 역순

#### `OrderService`

* 동일 productId가 여러 번 들어올 수 있으므로:

    * productId 기준 수량 합산
    * 합산된 항목을 productId 기준 정렬
    * 상품당 1회만 `debitTx(productId, qty)` 호출

#### `ProductService`

* 재고 변경은 `debitTx/chargeTx`에서 `SELECT ... FOR UPDATE`로 행 잠금
* (주의) 서비스 내부의 `@Transactional` 메서드는 **외부(다른 Bean)에서 호출될 때** 프록시가 적용됩니다.

---

### 1.6 통합 테스트(Integration Test) 정리

| 테스트                           | 목적              | 기대 결과                  |
| ----------------------------- | --------------- | ---------------------- |
| Wallet 동시 충전                  | 동일 유저 충전 정합성    | 합산된 최종 잔액 정확           |
| Wallet 동시 차감                  | 음수 잔액 방지        | 성공 횟수 한도 내, 잔액 0 미만 없음 |
| Coupon 동시 발급                  | 선착순/중복발급 방지     | 1회만 성공, 나머지 실패         |
| Coupon 동시 사용                  | 동일 쿠폰의 중복 사용 방지 | 1회만 USED 반영            |
| Order 동일 idempotencyKey 동시 요청 | 멱등성 보장          | 주문 1건만 생성              |
| Order 다수 유저 재고 경쟁             | 재고 한도 기반 정합성    | 성공 N건, 실패 M건(재고 기반)    |

> 테스트 파일 예시

* `src/test/java/kr/hhplus/be/server/order/OrderConcurrencyTest.java`
* `src/test/java/kr/hhplus/be/server/wallet/WalletConcurrencyTest.java`
* `src/test/java/kr/hhplus/be/server/coupon/CouponConcurrencyTest.java`

---

## 2. Cache (선택)

> 기존 문서에는 Spring Cache(`@Cacheable`) 방식 예시가 포함되어 있었으나, **현재 구현은 직접 Redis 캐시(Cache-Aside) + 스케줄 기반 갱신** 구조입니다. 아래 내용은 현재 코드 기준으로 정리합니다.

### 2.1 캐시 대상 선정

* 대상: **인기 상품 Top5 조회** (`GET /api/v1/products/top-selling`)
* 근거:

    * 조회 빈도가 높고(메인/추천/리스트), 데이터는 상대적으로 천천히 변합니다.
    * 판매량 TopN 집계/정렬 쿼리는 비용이 크므로 캐시 효율이 높습니다.

---

### 2.2 캐시 전략

* 패턴: **Cache-Aside(조회는 캐시 우선, 미스 시 DB fallback 후 캐시 적재)**
* 캐시 내용: **Top5 productId 리스트**(ID 캐시) → 상세(Product)는 DB 조회
* 키: `cache:products:top-selling:v1`
* TTL: **60초**
* 갱신: **주기적 갱신(스케줄러) + 조회 시 미스 fallback**

> 인기 상품은 “정확한 실시간성”보다 “조회 성능”이 더 중요한 영역으로 보고, TTL + 배치 갱신으로 타협점을 선택했습니다.

---

### 2.3 구현 요약

#### (1) 캐시 저장소 — `PopularProductCache`

* Redis value에 JSON(List<Long>)로 저장
* 역직렬화 실패/깨진 캐시는 **미스로 처리**

```java
private static final String KEY = "cache:products:top-selling:v1";
private static final Duration TTL = Duration.ofSeconds(60);
```

#### (2) 조회 경로 — `ProductService.getTopSellingProducts()`

1. Redis에서 Top5 ID 조회
2. 캐시 미스면 DB fallback (`PopularProductRepository.findTop5ProductIds()`)
3. 캐시에 set
4. ID 목록으로 상품 상세 조회 (`findByProductIdIn(ids)`) 후 응답 DTO 변환

> 참고: DB의 `IN` 조회는 결과 순서를 보장하지 않을 수 있으므로, 필요 시 `ids` 기준으로 응답을 재정렬하는 방식으로 순서 보장을 강화할 수 있습니다.

#### (3) 캐시 갱신 — `PopularProductCacheRefresher`

* 60초마다 Top5 집계를 수행하여 Redis 캐시 갱신
* 다중 인스턴스 환경에서 중복 갱신 방지를 위해 분산락 사용

| 기능         | 락 키                         | 목적                      |
| ---------- | --------------------------- | ----------------------- |
| 인기상품 캐시 갱신 | `lock:cache:top-selling:v1` | 스케줄러 갱신 작업을 1개 인스턴스만 수행 |

```java
@Scheduled(fixedDelay = 60_000)
public void refresh() {
  String lockKey = "lock:cache:top-selling:v1";
  String token = redisLockService.tryLock(lockKey, 1000, 10_000);
  if (token == null) return;
  try {
    List<Long> topIds = popularProductRepository.findTop5ProductIds();
    popularProductCache.setIds(topIds == null ? List.of() : topIds);
  } finally {
    redisLockService.unlock(lockKey, token);
  }
}
```

---

### 2.4 캐시 검증 방법

* 동일 “인기 상품 조회” API를 연속 호출 시:

    * 1회차: 캐시 미스 → DB 집계/조회
    * TTL(60초) 내 2회차 이후: 캐시 히트(Top5 ID) → 상품 상세만 조회
* 제출 기준에서는 아래 중 하나를 첨부하면 충분합니다.

    * DB 쿼리 로그(집계 쿼리 호출 횟수 감소)
    * 간단한 응답 시간 비교(상대 비교)

---

## 3. 결론

* **Distributed Lock(필수)**
    * `OrderFacade`에서 유저락 + 상품락(+쿠폰락)을 “주문 전체 구간”으로 확보하여 원자 구간을 명확히 했습니다.
    * 멀티 상품 주문은 productId 정렬로 교착 가능성을 낮췄습니다.
    * DB 트랜잭션(`FOR UPDATE`)과 혼용 시 주의점을 문서화하고 통합 테스트로 검증했습니다.

* **Cache(선택)**
    * 인기 상품 Top5 조회에 대해 **Redis 기반 Cache-Aside + 주기적 갱신(스케줄러) + 갱신 락** 구조를 적용했습니다.
    * TTL 기반 캐시 전략으로 단순하고 안정적인 운영 형태를 구성했습니다.
