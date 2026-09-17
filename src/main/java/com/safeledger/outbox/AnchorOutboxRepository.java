package com.safeledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnchorOutboxRepository extends JpaRepository<AnchorOutbox, Long> {

    /** 미전송분 — 오래된 것부터. 한 번에 다 보내지 않고 나눠 보내 외부 서버를 밀어붙이지 않는다. */
    List<AnchorOutbox> findTop50BySentAtIsNullOrderByIdAsc();

    Optional<AnchorOutbox> findByDocToken(String docToken);

    long countBySentAtIsNull();

    /** 가장 오래 밀린 미전송 행 — 구성 상태가 "몇 분째·마지막 오류"를 말하려고. */
    Optional<AnchorOutbox> findFirstBySentAtIsNullOrderByIdAsc();
}
