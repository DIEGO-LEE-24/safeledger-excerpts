package com.safeledger.coalescer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>같은 원장으로 두 번 계산하지 않는다.</b>
 *
 * <p>운영 실측: 예열이 도는 중에 사용자가 누르면 둘이 같은 LLM 런타임을 물어 <b>62.9초</b>가 나왔다
 * (따로 돌면 34.9초 · 캐시면 0.1초). <i>"겹치지 않게 기다린다"</i> 는 <b>무대에서 못 쓰는 조건</b>이다 —
 * 사람은 아무 때나 누른다. 그래서 겹치면 <b>두 번 계산하지 말고 하나를 기다린다.</b>
 *
 * <p>★이 성질은 <b>결과만 봐서는 확인할 수 없다</b> — 두 번 계산해도 답은 같아 보인다.
 * 다른 것은 <b>몇 번 계산했는가</b>뿐이라, 그 횟수를 여기서 센다.
 */
class AgentRunCoalescerTest {

    @Test
    @DisplayName("★같은 키가 겹치면 한 번만 계산한다(뒤에 온 쪽은 그 결과를 받는다)")
    void joinsInsteadOfComputingTwice() throws Exception {
        AgentRunCoalescer c = new AgentRunCoalescer();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID answer = UUID.randomUUID();

        Thread first = new Thread(() -> c.runOrJoin(1L, "advisor", "H", 5_000L, () -> {
            calls.incrementAndGet();
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return answer;
        }));
        first.start();
        assertTrue(started.await(3, TimeUnit.SECONDS), "첫 계산이 시작돼야 겹침을 만들 수 있다");

        Thread[] late = new Thread[3];
        UUID[] got = new UUID[3];
        for (int i = 0; i < 3; i++) {
            int idx = i;
            late[i] = new Thread(() -> got[idx] =
                    c.runOrJoin(1L, "advisor", "H", 5_000L, () -> { calls.incrementAndGet(); return UUID.randomUUID(); }));
            late[i].start();
        }
        /* ⚠**겹침을 결정적으로 만든다.** 처음엔 스레드를 띄우자마자 release 했더니,
             늦게 온 쪽이 **합류하기 전에 주인이 끝나** 새로 계산했다(calls=2).
           ★그건 코드 결함이 아니라 **테스트가 겹침을 못 만든 것**이었다 —
             셋이 실제로 대기 상태에 들어간 것을 보고 나서 놓아 준다. */
        for (int i = 0; i < 200 && !allWaiting(late); i++) Thread.sleep(10);
        assertTrue(allWaiting(late), "늦은 셋이 대기에 들어가야 겹침이 성립한다");
        release.countDown();
        first.join(5_000);
        for (Thread t : late) t.join(5_000);

        assertEquals(1, calls.get(), "겹친 4개가 한 번만 계산해야 한다 — 두 번 돌면 둘 다 느려진다");
        for (UUID g : got) assertEquals(answer, g, "뒤에 온 쪽은 앞의 결과를 그대로 받는다");
    }

    @Test
    @DisplayName("★회사가 다르면 합류하지 않는다 — 남의 회사 결과를 받으면 안 된다")
    void neverJoinsAcrossCompanies() {
        AgentRunCoalescer c = new AgentRunCoalescer();
        assertNotEquals(AgentRunCoalescer.key(1L, "advisor", "H"),
                AgentRunCoalescer.key(2L, "advisor", "H"),
                "회사가 키에 없으면 멀티테넌트에서 가장 비싼 버그가 된다");
        assertNotEquals(AgentRunCoalescer.key(1L, "advisor", "H"),
                AgentRunCoalescer.key(1L, "advisor", "H2"),
                "원장이 다르면 다른 계산이다");
    }

    @Test
    @DisplayName("계산이 끝나면 자리를 비운다 — 다음 요청이 새로 계산할 수 있어야 한다")
    void releasesAfterCompletion() {
        AgentRunCoalescer c = new AgentRunCoalescer();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++)
            c.runOrJoin(1L, "advisor", "H", 1_000L, () -> { calls.incrementAndGet(); return UUID.randomUUID(); });
        assertEquals(3, calls.get(), "겹치지 않은 호출까지 묶으면 낡은 결과를 준다");
    }

    @Test
    @DisplayName("앞선 계산이 실패해도 뒤에 선 쪽이 매달리지 않는다")
    void failureDoesNotStrandWaiters() throws Exception {
        AgentRunCoalescer c = new AgentRunCoalescer();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                c.runOrJoin(1L, "advisor", "H", 5_000L, () -> {
                    started.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    throw new IllegalStateException("LLM 실패");
                });
            } catch (RuntimeException ignored) { }
        });
        first.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));

        UUID fallback = UUID.randomUUID();
        long t0 = System.currentTimeMillis();
        Thread late = new Thread(() -> c.runOrJoin(1L, "advisor", "H", 5_000L, () -> fallback));
        late.start();
        release.countDown();
        late.join(5_000);
        first.join(5_000);
        assertTrue(System.currentTimeMillis() - t0 < 4_000,
                "앞이 죽으면 바로 깨워야 한다 — 상한까지 매달리면 사용자가 그만큼 기다린다");
    }

    @Test
    @DisplayName("계산이 예외를 던지면 그대로 올린다 — 조용히 삼키지 않는다")
    void propagatesFailure() {
        AgentRunCoalescer c = new AgentRunCoalescer();
        assertThrows(IllegalStateException.class,
                () -> c.runOrJoin(1L, "advisor", "H", 1_000L, () -> { throw new IllegalStateException("x"); }));
    }

    private static boolean allWaiting(Thread[] ts) {
        for (Thread t : ts) {
            Thread.State st = t.getState();
            if (st != Thread.State.WAITING && st != Thread.State.TIMED_WAITING) return false;
        }
        return true;
    }
}
