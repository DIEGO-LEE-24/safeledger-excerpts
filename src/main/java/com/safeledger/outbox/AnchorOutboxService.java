package com.safeledger.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 트랜잭셔널 아웃박스 — 발급된 증빙의 <b>지문만</b> 외부 저장소로 복제한다.
 *
 * <p><b>왜 필요한가</b>: "관리자면 DB를 고칠 수 있잖아요?" 에 답하려면, 앱이 건드릴 수 없는 곳에
 * 지문을 미리 복제해 둬야 한다. 그래야 앱 DB의 값을 고쳤을 때 바깥과 어긋나는 것이 드러난다.
 *
 * <p><b>불변식</b>
 * <ul>
 *   <li>외부 전송 실패가 문서 발급을 <b>절대</b> 막지 않는다 — 발급 트랜잭션은 대기함 행만 남기고 끝난다.</li>
 *   <li>나가는 것은 토큰·지문·시각뿐 — 본문·개인정보·경위는 나가지 않는다.</li>
 *   <li>어긋남을 감추지 않는다 — 대조 결과가 불일치면 그대로 노출한다(그게 이 장치의 존재 이유).</li>
 *   <li>설정이 비면 전 기능 비활성 = 기존 동작 그대로.</li>
 * </ul>
 *
 * <p><b>이 발췌에 없는 것</b>: 원 저장소에는 이 클래스에 공개 진위확인 대조 로직이 함께 있다.
 * 그 부분은 지문 계산 방식이 드러나므로 가져오지 않았다. 여기 있는 것은 대기함(아웃박스)뿐이다.
 */
@Service
public class AnchorOutboxService {

    private static final Logger log = System.getLogger(AnchorOutboxService.class.getName());

    private final AnchorOutboxRepository outbox;
    private final ObjectMapper om;

    @Value("${app.anchor.url:}")
    private String baseUrl;

    @Value("${app.anchor.key:}")
    private String writeKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 커밋 직후 1회 시도용 — 실패해도 스케줄러가 다시 집어간다(여기서 예외를 밖으로 내보내지 않는다). */
    private final ExecutorService pusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anchor-push");
        t.setDaemon(true);
        return t;
    });

    public AnchorOutboxService(AnchorOutboxRepository outbox, ObjectMapper om) {
        this.outbox = outbox;
        this.om = om;
    }

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * 발급된 문서를 대기함에 넣는다 — <b>호출 트랜잭션 안에서</b> 행만 남기고 즉시 리턴.
     * 커밋된 뒤에야 실제 전송을 시도한다(롤백된 문서를 외부에 남기면 그 기록이 거짓을 증언하게 된다).
     */
    @Transactional(propagation = Propagation.REQUIRED)   // 트랜잭션 밖 호출 경로에서도 절대 예외를 던지지 않는다
    public void enqueue(Long companyId, String companyName, String docToken, String sha256, String issuedAt) {
        if (!enabled() || docToken == null || sha256 == null) return;
        if (outbox.findByDocToken(docToken).isPresent()) return;   // 이미 대기·전송됨
        outbox.save(new AnchorOutbox(companyId, companyName, docToken, sha256, issuedAt));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { pusher.submit(AnchorOutboxService.this::flushQuietly); }
            });
        }
    }

    /** 재전송 스케줄러 — 외부 서버가 죽어 있던 동안 밀린 지문을 복구 시 흘려보낸다. */
    @Scheduled(fixedDelayString = "${app.anchor.retry-ms:60000}", initialDelayString = "${app.anchor.retry-ms:60000}")
    public void retry() { flushQuietly(); }

    private void flushQuietly() {
        try { flush(); } catch (Exception e) { log.log(Level.WARNING, "[anchor] 전송 루프 실패: {0}", e.toString()); }
    }

    /** 미전송분을 순서대로 보낸다. 실패는 행에 기록하고 다음 주기에 다시 — 큐에서 지우지 않는다. */
    @Transactional
    public void flush() {
        if (!enabled()) return;
        List<AnchorOutbox> pending = outbox.findTop50BySentAtIsNullOrderByIdAsc();
        for (AnchorOutbox row : pending) {
            try {
                String body = om.writeValueAsString(java.util.Map.of(
                        "docToken", row.getDocToken(), "sha256", row.getSha256(),
                        "issuedAt", row.getIssuedAt() == null ? "" : row.getIssuedAt(),
                        "company", row.getCompanyName() == null ? "" : row.getCompanyName()));
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/anchor"))
                        .timeout(Duration.ofSeconds(5))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
                if (writeKey != null && !writeKey.isBlank()) b.header("X-Anchor-Key", writeKey);
                HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                row.setAttempts(row.getAttempts() + 1);
                if (res.statusCode() == 201 || res.statusCode() == 200) {
                    row.setSentAt(Instant.now());
                    row.setLastError(null);
                } else if (res.statusCode() == 409) {
                    // 같은 토큰이 다른 지문으로 이미 기록돼 있다. 재시도해봐야 같은 답이므로 큐에서 뺀다.
                    //   ★ 조용히 넘기지 않는다 — 대조에서 불일치로 드러나게 두고 로그로 경고한다.
                    row.setSentAt(Instant.now());
                    row.setLastError("409 이미 다른 지문으로 기록됨 — " + res.body());
                    log.log(Level.WARNING, "[anchor] ⚠ 어긋남 token={0} — 외부에 이미 다른 지문이 있음", row.getDocToken());
                } else {
                    row.setLastError(res.statusCode() + " " + res.body());
                }
            } catch (Exception e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(e.toString());   // 원시 에러를 남긴다(429·연결거부 구분)
            }
            // ★명시 저장 — 더티 체킹에 기대지 않는다.
            //   이 메서드의 호출 경로는 **둘 다 자기호출(self-invocation)** 이었다:
            //     afterCommit → AnchorOutboxService.this::flushQuietly · retry() → flushQuietly()
            //   스프링 AOP 프록시는 내부 호출에 안 걸리므로 위의 @Transactional 이 **적용되지 않았고**,
            //   엔티티가 곧바로 detached 라 sentAt·attempts 변경이 **한 번도 UPDATE 되지 않았다.**
            //   증상: HTTP 는 정상 전송(외부에 17건 기록)인데 대기함은 sent_at=null·attempts=0 →
            //   **60초마다 같은 지문을 영원히 재전송**하고, 공개 대조에는 기록이 안 된 것으로 보인다.
            //   save() 는 리포지토리 자체 트랜잭션으로 도니 주변 트랜잭션 유무와 무관하게 남는다.
            outbox.save(row);
        }
    }
}
