package com.safeledger.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 멀티테넌트 — Hibernate {@code @TenantId} 디스크리미네이터 리졸버.
 *
 * <p>{@code @TenantId} 가 붙은 엔티티의 모든 SELECT·UPDATE·DELETE 에 companyId 조건이 자동
 * 주입되고, INSERT 때 companyId 가 자동으로 채워진다. 리포지토리와 서비스는 손대지 않는다.
 *
 * <p>★<b>null 을 돌려주면 안 된다.</b> Hibernate 는 null 테넌트를 'root' 로 취급해 <b>전체 조회</b>가
 * 된다 — 미인증 요청 하나가 전 회사 원장을 읽는다. 그래서 미인증·운영자(회사 없음) 요청은
 * <b>0L</b> 로 접는다. 0 은 어떤 회사와도 맞지 않으므로 아무것도 안 보인다.
 * "비어 있음"을 "전부"로 읽는 기본값은 이 부류에서 가장 비싼 기본값이다.
 */
public class TenantResolver implements CurrentTenantIdentifierResolver<Long> {

    /**
     * 시드 등 인증 컨텍스트가 없는 자리에서 회사를 강제 지정한다.
     *
     * <p>⚠ 직접 건드리지 않는다 — 해제를 한 번만 빠뜨려도 다른 회사 원장에 기록이 남는다.
     * 반드시 {@link #runAs} 로 감싼다(finally 복원을 타입이 강제한다).
     */
    private static final ThreadLocal<Long> OVERRIDE = new ThreadLocal<>();

    private static void override(Long companyId) { OVERRIDE.set(companyId); }

    private static void clear() { OVERRIDE.remove(); }

    /**
     * 그 회사 테넌트로 블록을 실행한다 — 항상 finally 로 <b>이전 값</b>을 복원한다.
     *
     * <p>★<b>재진입 안전.</b> runAs 안에서 다시 runAs 를 부르면, 안쪽이 끝날 때
     * 해제가 <b>바깥 회사까지 지워 버리던</b> 버그가 있었다. 그러면 바깥 블록의 남은 쿼리가
     * 테넌트 없이 나간다. 이전 값을 저장했다가 되돌리는 것으로 고쳤다(중첩이 아니면 종전과 같다).
     *
     * <p>★{@code @TenantId} 테넌트는 Hibernate 세션이 열릴 때 <b>한 번</b> 결정된다.
     * 트랜잭션(세션)이 이 블록 <b>안에서</b> 시작되게 해야 한다.
     *
     * <p>⚠ ThreadLocal 이라 <b>다른 스레드로 전파되지 않는다</b>.
     * ★종전 주석은 여기에 <i>"현재 비동기 미사용"</i> 이라고 적혀 있었는데 <b>더 이상 사실이 아니다</b>.
     * 예열용 전용 데몬 스레드가 생겼고 주기 작업도 스케줄러 스레드에서 돈다.
     * ⚠<b>없는 상황이라고 적힌 주석은, 그 상황이 생겨도 아무도 안 보게 만든다</b> — 그래서 사실로 고친다.
     * 배경 스레드에서 회사 자료를 만지려면 <b>그 스레드 안에서</b> runAs 를 부른다. 바깥에서 감싸면
     * 소용없다. 스케줄러가 여러 회사를 도는 자리는 아예 {@code @TenantId} 를 쓰지 않고 companyId 를
     * 평문 컬럼으로 두는 쪽이 맞다.
     */
    public static <T> T runAs(Long companyId, Supplier<T> block) {
        // companyId 가 null 이면 "미설정"과 구별되지 않아 조용히 호출자 테넌트로 실행된다(엉뚱한 회사에 기록).
        //   조용한 크로스테넌트 오염 대신 즉사시킨다 — 호출부 버그를 바로 드러낸다.
        Objects.requireNonNull(companyId, "runAs: companyId 가 null 입니다 — 호출부에서 회사 식별자를 확인하세요");
        Long prev = OVERRIDE.get();
        override(companyId);
        try { return block.get(); } finally { restore(prev); }
    }

    /** 반환값 없는 블록용. */
    public static void runAs(Long companyId, Runnable block) {
        Objects.requireNonNull(companyId, "runAs: companyId 가 null 입니다 — 호출부에서 회사 식별자를 확인하세요");
        Long prev = OVERRIDE.get();
        override(companyId);
        try { block.run(); } finally { restore(prev); }
    }

    /** 이전 값 복원 — 중첩이 아니면 제거(종전과 동일), 중첩이면 바깥 회사를 다시 세운다. */
    private static void restore(Long prev) {
        if (prev == null) clear(); else override(prev);
    }

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long o = OVERRIDE.get();
        if (o != null) return o;
        Long c = CurrentTenant.companyId();
        return c != null ? c : 0L;     // 미인증·운영자 → 0 (누수 차단)
    }

    @Override
    public boolean validateExistingCurrentSessions() { return false; }
}
