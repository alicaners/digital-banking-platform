import { check, sleep } from 'k6';
import { PAUSE, postJson, safeJson, prepareTestData } from './lib/common.js';

// Sistemin adresi lib/common.js içinde BASE_URL ortam değişkeninden okunur.
// Farklı bir adrese karşı çalıştırmak istersen:
// k6 run -e BASE_URL=http://baska-adres:8080 load-tests/transfer-load-test.js

export const options = {
    vus: 1,          // tek sanal kullanıcı (daha fazlası rate limiter'a takılır)
    duration: '1m',  // test süresi
    thresholds: {
        'http_req_duration{name:transfer}': ['p(95)<500'],
        'http_req_failed{name:transfer}': ['rate<0.01'],
        checks: ['rate>0.99'],
    },
};

// Test başlamadan önce BİR KERE çalışır: test verisini hazırlar.
export function setup() {
    return prepareTestData('lt');
}

// Test süresince tekrar tekrar çalışır. setup()'ın döndürdüğü veri 'data' olarak gelir.
export default function (data) {
    const res = postJson(
        '/api/transactions/transfer',
        {
            senderAccountId: data.senderAccountId,
            receiverAccountId: data.receiverAccountId,
            amount: 10,
        },
        data.token,
        { 'Idempotency-Key': `lt-${data.runId}-${__VU}-${__ITER}` },
        'transfer'
    );

    const ok = check(res, {
        'HTTP 200': (r) => r.status === 200,
        'transfer COMPLETED': (r) => safeJson(r, 'status') === 'COMPLETED',
    });

    if (!ok) {
        console.log(`[tur ${__ITER}] HTTP ${res.status} - ${res.body}`);
    }

    sleep(PAUSE);
}