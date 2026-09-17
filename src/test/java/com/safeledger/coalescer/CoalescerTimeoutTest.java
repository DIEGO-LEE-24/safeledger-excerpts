package com.safeledger.coalescer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * <b>못 기다렸다고 계산을 하나 더 시작하지 않는다.</b>
 *
 * <p>★종전엔 상한을 넘기면 {@code compute.get()} 으로 갔다. 그러면 <b>같은 원장을 두 번 계산</b>하는
 * 상태로 되돌아간다 — single-flight 가 막으려던 바로 그것이다.
 * <p>★<b>늦다는 것은 앞이 죽었다는 뜻이 아니라, 부하가 이미 높다는 뜻이다.</b>
 * 못 기다려서 하나 더 시작하는 것은 느린 상황에 부하를 더하는 것이고, 그래서 둘 다 더 느려진다
 * (실측 62.9초 · 동시 2회 재현에서 게이트웨이 <b>504 · 180초</b>).
 *
 * <p>⚠끝단으로는 이 차이를 못 본다 — 둘 다 "느리다"로 보인다. 그래서 단위로 못 박는다.
 */
class CoalescerTimeoutTest {

    /** 상한을 넘겨도 <b>두 번째 계산을 시작하지 않는다</b> — 이게 이 수리의 전부다. */
    @Test
    @DisplayName("★기다리다 상한에 닿으면 계산을 더 만들지 않고 말이 되는 응답을 낸다")
    void timeoutDoesNotStartASecondComputation() throws Exception {
        AgentRunCoalescer c = new AgentRunCoalescer();
        CountDownLatch leaderHolds = new CountDownLatch(1);
        CountDownLatch leaderStarted = new CountDownLatch(1);
        AtomicInteger computations = new AtomicInteger();

        Thread leader = new Thread(() -> c.runOrJoin(1L, "a", "h", 60_000L, () -> {
            computations.incrementAndGet();
            leaderStarted.countDown();
            try { leaderHolds.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return UUID.randomUUID();
        }));
        leader.start();
        leaderStarted.await(5, TimeUnit.SECONDS);

        // 합류자: 상한이 짧다. 앞이 아직 도는 중이다.
        var thrown = assertThrows(CoalescerBusyException.class,
                () -> c.runOrJoin(1L, "a", "h", 120L, () -> {
                    computations.incrementAndGet();       // ★여기가 늘면 수리가 죽은 것이다
                    return UUID.randomUUID();
                }));
        assertEquals(1, computations.get(), "★상한을 넘겼다고 두 번째 계산을 시작하면 안 된다");
        assertEquals(503, thrown.getStatus(),
                "사용자가 원인을 아는 응답이어야 한다 — 게이트웨이 504 는 아무것도 안 말한다");

        leaderHolds.countDown();
        leader.join(5_000);
    }

    /** ⚠<b>안 막는 쪽</b>: 앞이 <b>죽었으면</b> 직접 가는 것이 맞다 — 아무도 계산하고 있지 않으니까. */
    @Test
    @DisplayName("앞선 계산이 실패했으면 합류자가 직접 계산한다")
    void failedLeaderMeansTheJoinerComputes() throws Exception {
        AgentRunCoalescer c = new AgentRunCoalescer();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch joinerMayGo = new CountDownLatch(1);

        Thread leader = new Thread(() -> {
            try {
                c.runOrJoin(1L, "a", "h", 60_000L, () -> {
                    leaderStarted.countDown();
                    try { joinerMayGo.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    throw new IllegalStateException("앞선 계산이 죽었다");
                });
            } catch (RuntimeException expected) { /* 주인은 예외를 그대로 낸다 */ }
        });
        leader.start();
        leaderStarted.await(5, TimeUnit.SECONDS);

        UUID mine = UUID.randomUUID();
        Thread joiner = new Thread(() -> {
            UUID got = c.runOrJoin(1L, "a", "h", 5_000L, () -> mine);
            assertSame(mine, got, "앞이 죽었으면 내가 계산한 결과가 나와야 한다");
        });
        joiner.start();
        joinerMayGo.countDown();
        joiner.join(10_000);
        leader.join(5_000);
    }

    @Test
    @DisplayName("겹치지 않으면 그냥 자기 계산이다 — 관문이 정상 경로를 느리게 만들면 안 된다")
    void noContentionIsUntouched() {
        AgentRunCoalescer c = new AgentRunCoalescer();
        UUID mine = UUID.randomUUID();
        assertSame(mine, c.runOrJoin(1L, "a", "h", 1L, () -> mine));
    }
}
