package com.safeledger.coalescer;


import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * <b>같은 원장으로 두 번 계산하지 않는다.</b>
 *
 * <p>운영 실측: 원장 변경 → 70초 대기 → 첫 호출 <b>62.9초</b>(기준선 34.9). 예열이 안 돈 게 아니라
 * <b>예열과 사용자 호출이 같은 LLM 런타임을 동시에 물어</b> 둘 다 느려진 것으로 보였다.
 * 150초를 기다린 재측에서는 <b>0.1초</b>였다 — 겹치지만 않으면 캐시가 통한다.
 *
 * <p>★그런데 <b>"겹치지 않게 기다린다"는 무대에서 못 쓰는 조건</b>이다. 사람은 아무 때나 누른다.
 * ⇒ 겹치면 <b>두 번 계산하지 말고 하나를 기다린다</b>(single-flight).
 * 예열이 돌고 있는 중에 사용자가 누르면, 그 사용자는 <b>예열의 결과를 받는다.</b>
 *
 * <p>⚠<b>회사·원장 지문까지 키에 넣는다.</b> 에이전트 이름만으로 묶으면
 * <b>다른 회사의 계산을 기다리다 남의 결과를 받는다</b> — 멀티테넌트에서 가장 비싼 종류의 버그다.
 *
 * <p>⚠<b>기다림에도 끝이 있다.</b> 앞선 계산이 죽으면 뒤에 선 사람이 영원히 기다릴 수 있다 — 상한을 둔다.
 * 그런데 <b>상한을 넘겼다고 직접 계산하는 것도 틀렸다</b> — 늦다는 것은 앞이 죽었다는 뜻이 아니라
 * <b>부하가 이미 높다</b>는 뜻이고, 거기서 계산을 하나 더 시작하면 둘 다 더 느려진다.
 * 그래서 상한에 닿으면 <b>계산을 더 만들지 않고 503 을 낸다.</b> 그 사실은 로그에 남긴다.
 */
public class AgentRunCoalescer {

    private static final Logger log = System.getLogger(AgentRunCoalescer.class.getName());

    /** 진행 중인 계산. 키 = 회사|에이전트|원장지문 — 셋이 같아야 같은 계산이다. */
    private final ConcurrentHashMap<String, CompletableFuture<UUID>> inFlight = new ConcurrentHashMap<>();

    static String key(Long companyId, String agent, String ledgerHash) {
        return companyId + "|" + agent + "|" + ledgerHash;
    }

    /**
     * 같은 키의 계산이 돌고 있으면 <b>그 결과를 기다려 받고</b>, 없으면 내가 계산한다.
     *
     * @param compute 실제 계산(LLM 호출) — <b>키를 만든 쪽만</b> 부른다
     */
    public UUID runOrJoin(Long companyId, String agent, String ledgerHash, long waitMs, Supplier<UUID> compute) {
        String k = key(companyId, agent, ledgerHash);
        CompletableFuture<UUID> mine = new CompletableFuture<>();
        CompletableFuture<UUID> existing = inFlight.putIfAbsent(k, mine);

        if (existing != null) {          // 누가 이미 돌고 있다 — 기다린다
            try {
                UUID id = existing.get(waitMs, TimeUnit.MILLISECONDS);
                if (id != null) {
                    log.log(Level.INFO, "[advisor] 진행 중인 계산에 합류 — 다시 계산하지 않음 ({0})", k);
                    return id;
                }
                /* 앞이 `null` 로 끝났다 = **죽었다**는 신호(주인이 예외 때 그렇게 깨운다).
                   그때는 내가 직접 가는 것이 맞다 — 아무도 계산하고 있지 않으니까. */
                log.log(Level.WARNING, "[advisor] 앞선 계산이 실패했다 — 직접 계산합니다 ({0})", k);
                return compute.get();
            } catch (java.util.concurrent.TimeoutException e) {
                /* ★[심각] — **여기서 직접 계산하면 안 된다.**
                     종전엔 상한을 넘기면 `compute.get()` 으로 갔다. 그러면 **같은 원장을 두 번 계산**하는
                     상태로 되돌아간다 — single-flight 가 막으려던 바로 그것이다.
                   ★**늦다는 것은 앞이 죽었다는 뜻이 아니라, 부하가 이미 높다는 뜻이다.**
                     못 기다려서 계산을 하나 더 시작하는 것은 **느린 상황에 부하를 더하는 것**이고,
                     그래서 둘 다 더 느려진다(실측 62.9초 · 재현 측정 **동시 2회에서 게이트웨이 504·180초**).
                   ⚠기다림 상한은 **게이트웨이 타임아웃보다 짧아야** 한다 — 넘기면 사용자는
                     원인을 알 수 없는 504를 본다. 그래서 상한에 닿으면 **말이 되는 응답**을 준다. */
                log.log(Level.WARNING, "[advisor] 앞선 계산이 {0}ms 안에 안 끝났다 — 두 번째 계산을 시작하지 않는다 ({1})",
                        String.valueOf(waitMs), k);   // String.valueOf: MessageFormat 이 long 에 천단위 쉼표를 붙인다
                throw CoalescerBusyException.serviceUnavailable(
                        "아직 계산 중입니다 — 잠시 후 다시 시도해 주세요."
                        + " (같은 원장으로 두 번 계산하지 않으려고 기다렸습니다)");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw CoalescerBusyException.serviceUnavailable(
                        "계산 대기가 중단됐습니다 — 잠시 후 다시 시도해 주세요.");
            } catch (java.util.concurrent.ExecutionException e) {
                log.log(Level.WARNING, "[advisor] 앞선 계산이 예외로 끝났다 — 직접 계산합니다 ({0}): {1}",
                        k, e.getCause() == null ? e : e.getCause().getClass().getSimpleName());
                return compute.get();
            }
        }

        try {                            // 내가 주인이다
            UUID id = compute.get();
            mine.complete(id);
            return id;
        } catch (RuntimeException e) {
            mine.complete(null);         // 기다리던 쪽이 **바로** 폴백하도록 깨운다(영원히 매달리지 않게)
            throw e;
        } finally {
            inFlight.remove(k, mine);
        }
    }
}
