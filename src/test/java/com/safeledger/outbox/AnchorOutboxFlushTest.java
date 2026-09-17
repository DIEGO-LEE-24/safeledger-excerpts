package com.safeledger.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>보낸 것을 보냈다고 적지 않으면, 영원히 다시 보낸다.</b>
 *
 * <p>운영에서 이런 일이 있었다 — 외부 전송은 정상이었는데(외부에 17건 기록) 대기함은
 * {@code sent_at=null · attempts=0} 이었다. 그래서 스케줄러가 <b>60초마다 같은 지문을 다시 보냈고</b>,
 * 공개 대조에는 기록이 안 된 것으로 보였다.
 *
 * <p>원인은 <b>스프링 AOP 자기호출</b>이었다. {@code flush()} 에 붙은 {@code @Transactional} 은
 * 프록시를 거쳐 들어올 때만 적용되는데, 이 메서드의 호출 경로가 둘 다 같은 객체 안에서 시작했다 —
 * {@code afterCommit → this::flushQuietly} 와 {@code retry() → flushQuietly()}. 프록시를 안 타니
 * 트랜잭션이 없었고, 엔티티는 곧바로 detached 라 필드를 바꿔도 UPDATE 가 나가지 않았다.
 *
 * <p>★<b>이 성질을 재현하려면 대기함이 복사본을 돌려줘야 한다.</b> 같은 인스턴스를 돌려주면
 * {@code row.setSentAt(...)} 이 저장소 안의 그 객체를 제자리에서 바꿔 버려서, 명시 저장을 지워도
 * 테스트가 초록이 된다 — 결함을 못 잡는 테스트가 된다. 아래 {@code copyOf} 가 그 자리다.
 */
class AnchorOutboxFlushTest {

    private HttpServer server;
    private AtomicInteger posts;
    private AtomicInteger status;
    private Store store;
    private AnchorOutboxService service;

    /** 대기함 흉내. 이 코드가 실제로 부르는 것은 네 가지뿐이라, 그 넷만 응답한다. */
    static final class Store implements InvocationHandler {
        final Map<String, AnchorOutbox> rows = new LinkedHashMap<>();
        long seq = 0;

        /** ★detached 흉내 — 돌려주는 것은 복사본이다. 위 javadoc 참조. */
        static AnchorOutbox copyOf(AnchorOutbox r) {
            AnchorOutbox c = new AnchorOutbox(
                    r.getCompanyId(), r.getCompanyName(), r.getDocToken(), r.getSha256(), r.getIssuedAt());
            c.setId(r.getId());
            c.setSentAt(r.getSentAt());
            c.setAttempts(r.getAttempts());
            c.setLastError(r.getLastError());
            return c;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            switch (m.getName()) {
                case "save": {
                    AnchorOutbox r = (AnchorOutbox) args[0];
                    if (r.getId() == null) r.setId(++seq);
                    rows.put(r.getDocToken(), copyOf(r));
                    return r;
                }
                case "findByDocToken": {
                    AnchorOutbox r = rows.get((String) args[0]);
                    return r == null ? Optional.empty() : Optional.of(copyOf(r));
                }
                case "findTop50BySentAtIsNullOrderByIdAsc": {
                    List<AnchorOutbox> out = new ArrayList<>();
                    rows.values().stream()
                            .filter(r -> r.getSentAt() == null)
                            .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                            .forEach(r -> out.add(copyOf(r)));
                    return out;
                }
                case "countBySentAtIsNull":
                    return rows.values().stream().filter(r -> r.getSentAt() == null).count();
                default:
                    throw new UnsupportedOperationException(
                            "이 테스트가 예상하지 않은 호출: " + m.getName()
                            + " — 코드가 대기함에 새로 의존하기 시작했다는 뜻이다.");
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        posts = new AtomicInteger();
        status = new AtomicInteger(201);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anchor", ex -> {
            try (InputStream in = ex.getRequestBody()) { in.readAllBytes(); }
            posts.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        store = new Store();
        AnchorOutboxRepository repo = (AnchorOutboxRepository) Proxy.newProxyInstance(
                AnchorOutboxRepository.class.getClassLoader(),
                new Class<?>[]{AnchorOutboxRepository.class}, store);

        service = new AnchorOutboxService(repo, new ObjectMapper());
        // 원 코드는 스프링이 @Value 로 넣는다. 컨테이너 없이 돌리려고 여기서만 직접 넣는다.
        var f = AnchorOutboxService.class.getDeclaredField("baseUrl");
        f.setAccessible(true);
        f.set(service, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void enqueueRow(String token) {
        AnchorOutbox row = new AnchorOutbox(1L, "회사", token, "a".repeat(64), Instant.now().toString());
        store.invoke(null, method("save"), new Object[]{row});
    }

    private static Method method(String name) {
        for (Method m : AnchorOutboxRepository.class.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new IllegalStateException(name);
    }

    @Test
    @DisplayName("★한 번 보낸 행은 다음 주기에 다시 나가지 않는다(보냈다는 사실이 저장돼야 한다)")
    void doesNotResendWhatWasAlreadySent() {
        enqueueRow("tok-1");

        service.flush();
        assertEquals(1, posts.get(), "첫 주기에 한 번 나가야 한다");

        service.flush();
        assertEquals(1, posts.get(),
                "★여기가 늘면 '보냈다'가 저장되지 않은 것이다 — 60초마다 같은 지문을 영원히 다시 보내게 된다");

        assertNotNull(store.rows.get("tok-1").getSentAt(), "전송 시각이 남아야 한다");
        assertEquals(0, store.rows.values().stream().filter(r -> r.getSentAt() == null).count());
    }

    @Test
    @DisplayName("전송이 실패하면 큐에 남아 다음 주기에 다시 나간다(까닭도 남는다)")
    void keepsFailedRowInQueue() {
        status.set(500);
        enqueueRow("tok-2");

        service.flush();
        assertEquals(1, posts.get());
        assertNull(store.rows.get("tok-2").getSentAt(), "실패했으면 보냈다고 적으면 안 된다");
        assertTrue(store.rows.get("tok-2").getLastError().startsWith("500"), "원시 에러를 남긴다");

        service.flush();
        assertEquals(2, posts.get(), "다음 주기에 다시 시도해야 한다");
        assertEquals(2, store.rows.get("tok-2").getAttempts());
    }

    @Test
    @DisplayName("409(이미 다른 지문으로 기록됨)는 큐에서 빼되 까닭을 남긴다 — 재시도해도 답이 같다")
    void stopsRetryingOnConflictButRecordsWhy() {
        status.set(409);
        enqueueRow("tok-3");

        service.flush();
        service.flush();

        assertEquals(1, posts.get(), "같은 답이 올 것을 알면서 계속 두드리지 않는다");
        assertNotNull(store.rows.get("tok-3").getSentAt());
        assertTrue(store.rows.get("tok-3").getLastError().contains("409"),
                "★조용히 넘기지 않는다 — 어긋남은 대조에서 드러나야 하고, 까닭이 남아 있어야 한다");
    }

    @Test
    @DisplayName("설정이 비어 있으면 아무것도 하지 않는다(기존 동작 그대로)")
    void doesNothingWhenDisabled() throws Exception {
        var f = AnchorOutboxService.class.getDeclaredField("baseUrl");
        f.setAccessible(true);
        f.set(service, "");

        enqueueRow("tok-4");
        service.flush();

        assertEquals(0, posts.get());
        assertNull(store.rows.get("tok-4").getSentAt());
    }
}
