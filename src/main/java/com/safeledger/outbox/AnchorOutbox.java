package com.safeledger.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 외부 전송 대기함 — 발급된 증빙의 지문을 외부 서버로 보낼 때까지 담아 두는 큐.
 *
 * <p><b>왜 큐인가</b>: 외부 서버가 죽어 있다고 사고 기록·점검 마감이 실패하면 안 된다
 * (이 제품의 절대 규칙 — 안전 입력은 어떤 이유로도 막지 않는다). 그래서 발급 트랜잭션은
 * 이 행 하나만 남기고 끝나고, 실제 전송은 커밋 뒤 비동기로, 실패하면 스케줄러가 다시 시도한다.
 * 외부가 며칠 죽어 있어도 복구되면 밀린 것이 흘러간다.
 *
 * <p><b>이 표에는 테넌트 필터를 걸지 않는다</b>: 재전송 스케줄러는 로그인 컨텍스트 밖에서 돌아
 * 테넌트가 비어 있고, 그 상태로 테넌트 필터가 걸리면 아무 행도 못 읽어 큐가 영원히 밀린다.
 * 대신 이 표는 지문·토큰뿐이라 회사 간 내용 누출 소지가 없다(본문이 없다).
 */
@Entity
@Table(name = "anchor_outbox",
       indexes = @Index(name = "ix_anchor_outbox_company", columnList = "company_id"))
public class AnchorOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 평문 — 위 주석의 이유로 테넌트 필터를 걸지 않는다. */
    private Long companyId;

    @Column(length = 120)
    private String companyName;

    /** 문서 공개 토큰 = 외부 레코드의 키. */
    @Column(nullable = false, unique = true, length = 80)
    private String docToken;

    /** 문서 무결성 지문 — 외부로 나가는 유일한 실체. 본문은 나가지 않는다. */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** 앱 서버 발급 시각(외부 서버는 자기 시각도 따로 찍는다). */
    @Column(length = 40)
    private String issuedAt;

    private Instant createdAt;

    /** null 이면 아직 미전송 — 스케줄러가 집어가는 대상. */
    private Instant sentAt;

    private int attempts;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    protected AnchorOutbox() {
    }

    public AnchorOutbox(Long companyId, String companyName, String docToken, String sha256, String issuedAt) {
        this.companyId = companyId;
        this.companyName = companyName;
        this.docToken = docToken;
        this.sha256 = sha256;
        this.issuedAt = issuedAt;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCompanyId() { return companyId; }

    public String getCompanyName() { return companyName; }

    public String getDocToken() { return docToken; }

    public String getSha256() { return sha256; }

    public String getIssuedAt() { return issuedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
