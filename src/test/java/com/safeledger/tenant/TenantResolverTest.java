package com.safeledger.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>테넌트 컨텍스트는 "지금 누구의 자료를 보는가"다. 이게 틀리면 남의 회사 원장이 보인다.</b>
 *
 * <p>원 저장소에는 이 클래스 전용 테스트가 <b>없었다</b>. 고쳤다고 말할 수 있는 성질 중 둘에
 * 회귀 테스트가 없다는 것을 발췌를 준비하면서 알았고, 그래서 여기서 새로 썼다.
 * 아래 네 가지는 전부 <b>한 번씩 실제로 틀렸던</b> 자리다.
 *
 * <ol>
 *   <li>중첩 runAs 가 끝날 때 해제가 바깥 회사까지 지웠다 → 바깥의 남은 쿼리가 테넌트 없이 나갔다.</li>
 *   <li>블록 안에서 예외가 나도 복원이 돼야 한다(finally 가 없으면 회사가 새는 채로 남는다).</li>
 *   <li>companyId 가 null 이면 조용히 호출자 테넌트로 실행됐다 → 즉시 실패로 바꿨다.</li>
 *   <li>미인증이면 Hibernate 가 null 을 'root'(전체 조회)로 읽는다 → 0L 로 접는다.</li>
 * </ol>
 */
class TenantResolverTest {

    private final TenantResolver resolver = new TenantResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long companyId) {
        var token = new UsernamePasswordAuthenticationToken("u@example.com", "n/a", List.of());
        token.setDetails(companyId);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    @DisplayName("★중첩 runAs 가 끝나도 바깥 회사가 살아 있다(안쪽 해제가 바깥을 지우면 안 된다)")
    void nestedRunAsRestoresOuterTenant() {
        TenantResolver.runAs(10L, () -> {
            assertEquals(10L, resolver.resolveCurrentTenantIdentifier());

            TenantResolver.runAs(20L, () -> {
                assertEquals(20L, resolver.resolveCurrentTenantIdentifier(), "안쪽 블록은 안쪽 회사다");
            });

            assertEquals(10L, resolver.resolveCurrentTenantIdentifier(),
                    "★여기가 10이 아니면 안쪽 해제가 바깥까지 지운 것이다 — 이 블록의 남은 쿼리가 남의 회사로 나간다");
        });
    }

    @Test
    @DisplayName("안쪽에서 예외가 나도 바깥 회사가 복원된다")
    void restoresOuterTenantEvenWhenInnerThrows() {
        TenantResolver.runAs(10L, () -> {
            assertThrows(IllegalStateException.class, () ->
                    TenantResolver.runAs(20L, () -> {
                        throw new IllegalStateException("안쪽이 터졌다");
                    }));

            assertEquals(10L, resolver.resolveCurrentTenantIdentifier(),
                    "예외 경로에도 복원이 돼야 한다 — 그래서 finally 다");
        });
    }

    @Test
    @DisplayName("블록이 끝나면 회사가 남지 않는다(중첩이 아닌 경우 종전 동작 그대로)")
    void leavesNothingBehindAfterOutermostBlock() {
        TenantResolver.runAs(10L, () -> assertEquals(10L, resolver.resolveCurrentTenantIdentifier()));

        assertEquals(0L, resolver.resolveCurrentTenantIdentifier(),
                "블록 밖은 미인증이므로 0 이어야 한다 — 10 이 남아 있으면 그 스레드가 계속 남의 회사로 일한다");
    }

    @Test
    @DisplayName("★runAs(null) 은 조용히 넘어가지 않고 즉시 실패한다")
    void nullCompanyIdFailsFast() {
        var e = assertThrows(NullPointerException.class,
                () -> TenantResolver.runAs((Long) null, () -> "쓰기"));

        assertTrue(e.getMessage().contains("companyId"),
                "★조용히 호출자 테넌트로 실행되면 엉뚱한 회사에 기록이 남는다 — 그래서 즉사시킨다");
    }

    @Test
    @DisplayName("★미인증이면 0 이다(null 을 돌려주면 Hibernate 가 전체 조회로 읽는다)")
    void unauthenticatedResolvesToZeroNotNull() {
        Long tenant = resolver.resolveCurrentTenantIdentifier();

        assertEquals(0L, tenant);
        assertNotEquals(null, tenant,
                "★null 은 'root' = 전체 조회다. 미인증 요청 하나가 전 회사 원장을 읽게 된다");
    }

    @Test
    @DisplayName("인증돼 있으면 그 회사로 풀린다")
    void authenticatedResolvesToItsCompany() {
        authenticateAs(42L);
        assertEquals(42L, resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    @DisplayName("운영자(소속 회사 없음)도 0 이다 — 전체 조회가 아니다")
    void operatorWithoutCompanyResolvesToZero() {
        authenticateAs(null);
        assertEquals(0L, resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    @DisplayName("⚠runAs 는 다른 스레드로 전파되지 않는다 — 이 한계를 테스트로 못 박아 둔다")
    void doesNotPropagateToOtherThreads() throws Exception {
        var seen = new AtomicReference<Long>();
        var done = new CountDownLatch(1);

        TenantResolver.runAs(10L, () -> {
            Thread worker = new Thread(() -> {
                seen.set(resolver.resolveCurrentTenantIdentifier());
                done.countDown();
            });
            worker.start();
            try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });

        assertEquals(0L, seen.get(),
                "★배경 스레드는 바깥 회사를 물려받지 않는다. 그 스레드 안에서 다시 runAs 를 불러야 한다");
    }
}
