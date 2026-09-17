package com.safeledger.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;

/**
 * 현재 요청의 테넌트(companyId)·역할·이메일 — 인증 필터가 JWT 를 읽어 보안 컨텍스트에 넣어 둔 값.
 *
 * <p>서비스 계층은 이 값으로 원장 쿼리를 회사 단위로 스코프한다.
 * 운영자 계정은 소속 회사가 없어 {@code companyId()} 가 null 이다 —
 * 그 null 을 {@link TenantResolver} 가 0L 로 접는다. 그 까닭은 거기에 적혀 있다.
 */
public final class CurrentTenant {

    private CurrentTenant() {
    }

    private static Authentication auth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** 현재 회사 id — 운영자는 소속 회사가 없어 null. */
    public static Long companyId() {
        Authentication a = auth();
        return (a != null && a.getDetails() instanceof Long l) ? l : null;
    }

    /** 'admin' | 'foreman' | 'worker' | 'super' (없으면 null) */
    public static String role() {
        Authentication a = auth();
        if (a == null) return null;
        return a.getAuthorities().stream().findFirst()
                .map(g -> g.getAuthority().replace("ROLE_", "").toLowerCase(Locale.ROOT))   // 식별자다 — 인증 필터와 짝
                .orElse(null);
    }

    public static String email() {
        Authentication a = auth();
        return a == null ? null : String.valueOf(a.getPrincipal());
    }
}
