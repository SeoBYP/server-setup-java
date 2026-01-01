# 쿠폰 발급/주문 E2E 부하 테스트 장애 회고(Postmortem)

**— RATE=50 발생 → RATE=300 안정화 완료**

---

## 1) 사건 개요 (Incident Summary)

* **사건명**: 쿠폰 발급/주문 E2E 부하 테스트 중 Timeout·5xx 급증 및 실패 판정 오류
* **테스트 시나리오**: k6 E2E (쿠폰 발급 → 주문 생성)
* **초기 조건**: RATE=50 req/s, DURATION=60s (constant-arrival-rate)
* **최종 검증 조건**: **RATE=300**, DURATION=60s, PRE_VUS=50, MAX_VUS=500
* **최종 결과**: RATE=300에서도 **timeout 없음, 5xx 최소화, 테스트 정상 종료**

### 영향 요약

* Kafka/DB/Redis/Consumer 로직 불일치로 인해

    * 쿠폰 발급 요청에서 **timeout(status=0)**, **500** 다수 발생
    * 정상 비즈니스 실패(소진/이미 발급)가 “Unexpected error”로 처리됨

* k6 지표 해석 오류로

    * 기능적으로는 정상 동작했음에도 **부하 테스트가 실패로 판정**

* 동시성·재시도·오류 로깅이 결합되며 **장애가 증폭**됨

* **심각도**: SEV-2
  (핵심 사용자 플로우 장애 + 부하 상황에서 증폭 가능)

---

## 2) 탐지 (Detection)

### 2-1. 부하 테스트 지표

* `http_req_failed` 급등
* 쿠폰 claim 로그에 `status=0`(timeout) 다수
* `checks_succeeded=100%`인데도 threshold 실패

### 2-2. 서버/Consumer 로그

* `Duplicate entry` (UNIQUE 제약 위반)
* Hibernate 세션 오류 `HHH000099`
* Kafka Consumer에서 `ALREADY_USED` 반복
* `DB_CONFLICT`, `Unexpected error` 과다 로깅

---

## 3) 원인 요약 (Executive Root Cause)

이번 장애는 **단일 원인이 아니라, 여러 레이어의 문제들이 순차적으로 결합되며 증폭된 사건**이다.

1. **Kafka 브로커 미기동 상태에서 부하 테스트 시작**

    * 쿠폰 발급 요청이 대량 **timeout(status=0)** 발생

2. Kafka 기동 후, **동시성 경쟁에서 발생한 UNIQUE 위반**이

    * Hibernate 세션 오염(HHH000099),
    * Consumer 예외 전파 → Kafka 재시도(최대 3회),
    * MySQL **REPEATABLE READ**에서 재조회 실패
      로 이어지며 **500(DB_CONFLICT)** 로 확대됨

3. **Redis Lua 결과값 의미와 서버 매핑 불일치**

    * `r == 0`(remain <= 0, 소진)을 `ALREADY_USED`로 잘못 해석
    * 정상적인 “품절” 상황이 비정상 예외로 처리됨

4. **Redis 재고 초기화 로직 오류**

    * `initRemainIfAbsent(..., 1)`로 재고가 항상 1로 초기화
    * DB에 설정된 쿠폰 수량과 불일치 → 조기 소진

5. **k6 실패 판정 지표 오해**

    * 409/410을 정상으로 보더라도
      `http_req_failed`는 기본적으로 4xx/5xx를 실패로 집계
    * 기능은 정상인데 테스트는 실패로 판정

6. 일부 수정 사항이 **빌드/재기동 누락으로 미반영**

    * 복구 및 원인 확인이 지연됨

---

## 4) 타임라인 (Timeline) — RATE=50 기준 재현/검증

| 단계 | 관측 증상                         | 원인                         | 조치                   | 결과           |
| -- | ----------------------------- | -------------------------- | -------------------- | ------------ |
| 1  | 쿠폰 claim timeout(status=0) 급증 | Kafka 브로커 미기동              | Kafka/Zookeeper 기동   | timeout 급감   |
| 2  | Duplicate 이후 HHH000099        | UNIQUE 위반 후 세션 오염          | 예외 시 영속성 컨텍스트 clear  | 세션 오류 감소     |
| 3  | 동일 케이스 최대 3회 반복               | Consumer 예외 전파 → Kafka 재시도 | Consumer 내부에서 정상 처리  | 반복 패턴 완화     |
| 4  | UNIQUE 후 재조회 실패 → 500         | REPEATABLE READ 스냅샷        | 재조회 제거, 즉시 409 처리    | 500 → 409 수렴 |
| 5  | ALREADY_USED 로그 과다            | Redis 결과 의미 오해             | `r==0` → SOLD_OUT 매핑 | 로그/응답 의미 정상화 |
| 6  | 쿠폰이 1장처럼 동작                   | Redis 재고 1 고정 초기화          | DB 수량 기반 초기화         | 정상 발급 수량 회복  |
| 7  | checks 100%인데 run 실패          | k6 지표 정의 오류                | expected response 지정 | run 성공       |
| 8  | 수정 후에도 증상 지속                  | 코드 미반영                     | clean build + 재기동    | 변경 사항 반영 확인  |

---

## 5) 복구 및 개선 조치 (What Changed)

### 5-1. 쿠폰 발급 / Consumer / Redis

* Redis Lua 결과 의미 정정

    * `r == 0` → **SOLD_OUT**
* Redis 재고 초기화 정책 수정

    * DB 쿠폰 수량 기준으로 remain 세팅
* UNIQUE 위반은 즉시 **409(이미 발급)** 로 수렴
* Consumer에서 “정상 실패(소진/이미 발급)”를 명시 처리

    * 예외 전파 및 재시도 차단
* Hibernate 세션 오염 방지 처리 추가

### 5-2. 부하 테스트(k6)

* `expected response`에 정상 상태코드 명시

    * 쿠폰 claim: 200 / 409 / 410
    * 주문 생성: 201 / 409
* `http_req_failed`가 정상 실패를 실패로 집계하지 않도록 조정

### 5-3. 운영/프로세스

* Kafka readiness 사전 체크
* clean build + 재기동 절차 명문화
* 실행 중인 커밋/빌드 버전 확인 절차 추가

---

## 6) 최종 검증 (Validation)

### 성공 기준

* timeout(status=0) 재발 없음
* 5xx 비율이 임계치 이하로 안정화
* 동시성 상황에서 결과가

    * 일부 성공 + 나머지 409/410으로 **일관되게 수렴**
* 동일 userId 3회 반복 처리 패턴 제거
* k6 run 정상 종료

### 검증 결과

* RATE=50 → 문제 해결 확인
* **RATE=300, DURATION=60s, PRE_VUS=50, MAX_VUS=500**에서도 정상 종료

```bash
$env:RATE="300"; $env:DURATION="60s"; $env:PRE_VUS="50"; $env:MAX_VUS="500"; k6 run .\k6-e2e-coupon-order.js
```

---

## 7) 재발 방지(Action Items)

| 우선순위 | 항목                 | 내용                         |
| ---: | ------------------ | -------------------------- |
|   P0 | Kafka readiness 체크 | 미기동 상태에서 테스트 시작 차단         |
|   P0 | UNIQUE 위반 정책       | 500 확대로 이어지지 않고 409로 수렴    |
|   P0 | Redis 재고 초기화       | DB 수량과 항상 동기화              |
|   P0 | Consumer 예외 표준화    | 정상 실패는 예외 전파 금지            |
|   P1 | 트랜잭션 가이드           | REPEATABLE READ 재조회 한계 명문화 |
|   P1 | k6 지표 정의           | 정상/실패 상태코드 기준 문서화          |
|   P1 | 고부하 회귀 테스트         | RATE=50/300 정기 실행          |

---

## 8) 결론 (Closing)

이번 장애는 **Kafka 미기동 → 동시성 UNIQUE 위반 → Redis/Consumer 의미 불일치 → k6 지표 오해**가 단계적으로 결합되며 증폭된 사례였다.
핵심 개선은 다음 세 가지다.

1. **동시성 실패를 “시스템 오류”로 확대하지 않고 비즈니스 결과(409/410)로 수렴**
2. **Redis/Consumer/응답 코드의 의미를 완전히 일치**
3. **부하 테스트 지표의 의미를 정확히 정의**

그 결과, 동일한 E2E 시나리오가 **RATE=300 환경에서도 안정적으로 종료**되었으며, 재현·검증·회귀가 가능한 상태로 정리되었다.
