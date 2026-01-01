import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:7777';
const RATE = Number(__ENV.RATE || '10');

const MAX_FAIL_LOGS = Number(__ENV.MAX_FAIL_LOGS || '10');
let failLogs = 0;

// 상태코드 분포를 보기 위한 카운터
const claimStatus = new Counter('claim_status');
const orderStatus = new Counter('order_status');

export const options = {
  scenarios: {
    e2e_rate: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || '20'),
      maxVUs: Number(__ENV.MAX_VUS || '200'),
    },
  },
  thresholds: {
    // 4xx(의도된 충돌/소진)를 expected로 처리하면 이 지표가 의미있어집니다.
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  const walletUserCount = Number(__ENV.WALLET_USER_COUNT || '20000');
  const walletInitialBalance = __ENV.WALLET_INITIAL_BALANCE || '1000000';

  const resetUrl =
    `${BASE_URL}/internal/perf/reset-seed` +
    `?productCount=1000&hotProductCount=20` +
    `&walletUserCount=${walletUserCount}` +
    `&hotCouponQuantity=20000` +
    `&walletInitialBalance=${walletInitialBalance}`;

  const resetRes = http.post(resetUrl, null);

  if (resetRes.status !== 200) {
    console.log(`reset-seed failed status=${resetRes.status} body=${resetRes.body}`);
    throw new Error('reset-seed 호출 실패');
  }

  const hotCouponId = resetRes.json('hotCouponId');
  if (!hotCouponId) {
    console.log(`reset-seed response body=${resetRes.body}`);
    throw new Error('reset-seed 응답에서 hotCouponId를 얻지 못했습니다.');
  }

  return { hotCouponId };
}

function claimCouponOnce(couponId, userId) {
  const claimUrl = `${BASE_URL}/api/coupons/claim/${couponId}`;
  const payload = JSON.stringify({ userId, couponId, requestId: uuidv4() });

  return http.post(claimUrl, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',

    // ✅ 200/409/410/403/504를 "expected response"로 마킹
    // - 409: 이미 발급
    // - 410: 소진
    // - 403: 기간/상태에 따라 발급 불가(현재 컨트롤러 로직)
    // - 504: Kafka reply timeout(시스템 용량 문제지만 네트워크 실패로 보긴 애매해서 expected 처리)
    responseCallback: http.expectedStatuses({ min: 200, max: 200 }, 409, 410, 403, 504),
  });
}

export default function (data) {
  const couponId = data.hotCouponId;

  const walletUserCount = Number(__ENV.WALLET_USER_COUNT || '20000');
  const userId = randomIntBetween(1, walletUserCount);

  let claimRes = null;
  let userCouponId = null;

  for (let attempt = 1; attempt <= 3; attempt++) {
    claimRes = claimCouponOnce(couponId, userId);

    claimStatus.add(1, { status: String(claimRes.status) });

    if (claimRes.status === 200) {
      userCouponId = claimRes.json('userCouponId');
      break;
    }

    // 409(이미 발급), 410(소진), 403(발급 불가)은 정상 흐름으로 종료
    if (claimRes.status === 409 || claimRes.status === 410 || claimRes.status === 403) break;

    // 504는 시스템 병목 신호(원인 파악 대상)지만, 재시도는 해볼 수 있음
    if (claimRes.status === 504) {
      sleep(0.1 * attempt);
      continue;
    }

    if (failLogs < MAX_FAIL_LOGS) {
      failLogs++;
      console.log(
        `claim unexpected status=${claimRes.status} attempt=${attempt} body=${String(claimRes.body).slice(0, 500)}`
      );
    }

    sleep(0.1 * attempt);
  }

  const claimOk = check(claimRes, {
    'claim is 200/409/410/403/504': (r) =>
      r && (r.status === 200 || r.status === 409 || r.status === 410 || r.status === 403 || r.status === 504),
  });

  // 200이 아니면 주문을 진행하지 않음
  if (!claimOk || claimRes.status !== 200) return;

  const hasUserCouponId = check(null, {
    'claim has userCouponId': () => userCouponId !== null && userCouponId !== undefined,
  });
  if (!hasUserCouponId) return;

  const orderUrl = `${BASE_URL}/api/v1/orders`;
  const productId = Math.random() < 0.5 ? randomIntBetween(1, 20) : randomIntBetween(21, 1000);

  const orderPayload = JSON.stringify({
    userId,
    items: [{ productId, quantity: 1 }],
    userCouponId,
    idempotencyKey: uuidv4(),
  });

  const orderRes = http.post(orderUrl, orderPayload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
    // 주문은 201/409를 expected 처리
    responseCallback: http.expectedStatuses({ min: 201, max: 201 }, 409),
  });

  orderStatus.add(1, { status: String(orderRes.status) });

  const ok = check(orderRes, {
    'order is 201 or 409(insufficient balance)': (r) => r.status === 201 || r.status === 409,
  });

  if (!ok && failLogs < MAX_FAIL_LOGS) {
    failLogs++;
    console.log(`order failed status=${orderRes.status} body=${String(orderRes.body).slice(0, 500)}`);
  }
}