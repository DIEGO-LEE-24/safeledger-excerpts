# SafeLedger 발췌 — single-flight · 트랜잭셔널 아웃박스 · 멀티테넌트 격리

[![tests](https://github.com/DIEGO-LEE-24/safeledger-excerpts/actions/workflows/test.yml/badge.svg)](../../actions)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue)](pom.xml)

**이상철** · 백엔드 · 2026.06 ~ 운영 중 · 팀 4인 중 서버 전담(설계·구현·CI·배포·운영)
diegolee5494@gmail.com

**SafeLedger** 는 산업단지 협력사의 안전 점검·사고 기록을 회사별로 격리해 관리하는 SaaS 입니다.
발급된 증빙의 지문을 외부 저장소에 복제해 두어, 앱 DB 를 고쳤을 때 어긋남이 드러나게 합니다.

이 저장소는 그 백엔드에서 **제가 단독으로 쓴 파일만** 골라 옮긴 발췌본입니다.
셋 다 토이 프로젝트가 아니라 **운영에서 실제로 난 사고**를 고친 코드이고,
이력서에 적은 주장을 **명령 한 줄로 확인**하실 수 있게 테스트까지 같이 옮겼습니다.

| 용어 | 뜻 |
| --- | --- |
| 원장 | 회사별 안전 기록 장부. 모든 조회가 회사 단위로 격리된다 |
| 증빙 · 지문 | 발급된 문서와 그 SHA-256 해시 |
| 예열 | 사용자가 누르기 전에 미리 AI 권고를 계산해 두는 백그라운드 작업 |

---

## 60초 요약 — 문제 → 결정 → 수치

| 묶음 | 운영에서 실제로 난 문제 | 결정 | 수치 (이 저장소에서 재현됨) |
| --- | --- | --- | --- |
| **single-flight** | 예열과 사용자 클릭이 같은 원장을 동시에 물어 둘 다 느려졌다. 단독 34.9초가 겹치면 62.9초, 동시 2회는 게이트웨이 504·180초 | 겹치면 두 번 계산하지 않고 하나를 기다린다. 상한을 넘겨도 두 번째 계산을 시작하지 않고 503 | 겹친 4개 호출의 **계산 4회 → 1회** |
| **트랜잭셔널 아웃박스** | 외부 전송은 정상이었는데 대기함에 "보냈다"가 안 적혀 **60초마다 같은 지문을 영원히 재전송**했다 | 더티 체킹에 기대지 않고 명시 저장 | 두 번째 주기의 **POST 2회 → 1회** |
| **테넌트 컨텍스트** | 중첩 `runAs` 가 끝날 때 해제가 바깥 회사까지 지워, 바깥 블록의 남은 쿼리가 테넌트 없이 나갔다 | 이전 값을 저장했다가 복원. 미인증은 null 이 아니라 0 | 전용 회귀 테스트 **0건 → 8건** |

시간 수치(34.9초·62.9초·180초)는 운영에서 손으로 잰 기록이고 이 저장소에는 그 로그가 없습니다.
여기 테스트가 못 박는 것은 시간이 아니라 **횟수**입니다.

---

## 실행과 확인

**요구사항** — JDK 17 이상 · Maven 3.9 이상 · 최초 1회 의존성 다운로드(인터넷 필요).
`pom.xml` 의 `maven.compiler.release` 가 17 이라 JDK 11 에서는 컴파일 단계에서 멈춥니다.

```bash
git clone https://github.com/DIEGO-LEE-24/safeledger-excerpts
cd safeledger-excerpts
mvn test
```

실제 출력(끝부분):

```console
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.safeledger.coalescer.AgentRunCoalescerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.safeledger.coalescer.CoalescerTimeoutTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.safeledger.outbox.AnchorOutboxFlushTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.safeledger.tenant.TenantResolverTest
[INFO] Results:
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`-q` 를 붙이면 로그가 ERROR 로 내려가 통과했을 때 화면이 비어 있습니다. 확인이 목적이면 붙이지 마십시오.

## 주장과 확인

| 주장 | 코드 | 확인 명령 | 결과 |
| --- | --- | --- | --- |
| 전부 초록이다 | [src 트리](#파일-지도) | `mvn test` | `Tests run: 20, Failures: 0` |
| 겹쳐 들어온 4개 호출이 **계산을 1회만** 돈다. 상한을 넘겨도 두 번째 계산을 시작하지 않고 503 을 낸다 | [AgentRunCoalescer.java](src/main/java/com/safeledger/coalescer/AgentRunCoalescer.java) | `mvn test -Dtest=AgentRunCoalescerTest,CoalescerTimeoutTest` | 초록 8건 · **되돌리면 1건 빨강** |
| 보낸 것을 보냈다고 적지 않으면 60초마다 같은 지문을 다시 보낸다 | [AnchorOutboxService.java](src/main/java/com/safeledger/outbox/AnchorOutboxService.java) | `mvn test -Dtest=AnchorOutboxFlushTest` | 초록 4건 · **되돌리면 POST 가 1→2** |
| 중첩 `runAs` 가 끝나도 바깥 회사가 살아 있다. 미인증은 null 이 아니라 0 이다 | [TenantResolver.java](src/main/java/com/safeledger/tenant/TenantResolver.java) | `mvn test -Dtest=TenantResolverTest` | 초록 8건 · **되돌리면 6건 빨강** |

## 본인 기여 경계

SafeLedger 는 4인 팀 프로젝트입니다(백엔드 1 · 프론트 1 · 안전 도메인/QA 1 · 법령 데이터 1).
제가 맡은 것은 백엔드 영역 전부이고, 프론트와 법령 RAG 파트는 팀원이 맡았습니다.

- 이 저장소에 담긴 파일은 `git blame` 기준 **전부 본인 단독 작성분**입니다.
  팀원이 쓴 줄이 한 줄이라도 있던 파일은 가져오지 않았거나, 그 부분을 새로 썼습니다.
- 팀원 동의를 받고 공개했습니다. 확인이 필요하시면 원 저장소의 blame 화면을 보여 드리겠습니다.
- 원 저장소 2곳(SafeLedger · ANCLA)은 비공개이며 요청 시 열람 권한을 드립니다.

## 파일 지도

```
src/main/java/com/safeledger/
  coalescer/AgentRunCoalescer.java          겹친 요청을 하나로 접는다
  coalescer/CoalescerBusyException.java     상한 초과 시 돌려줄 503
  outbox/AnchorOutboxService.java           커밋 뒤 전송 · 재시도 스케줄러
  outbox/AnchorOutbox.java                  대기함 엔티티
  outbox/AnchorOutboxRepository.java
  tenant/TenantResolver.java                회사 식별자 해석 · runAs
  tenant/CurrentTenant.java                 인증 컨텍스트에서 회사 읽기
src/test/java/com/safeledger/               @Test 20건
scripts/scan-forbidden.sh                   공개 금칙 문자열 검사(CI 가 매 push 실행)
```

## 되돌려서 빨간불을 확인하는 법

테스트가 통과하는 것보다 **그 테스트가 무언가를 실제로 지키고 있는지**가 중요합니다.
세 군데 모두 수리를 옛 동작으로 되돌려 확인했습니다.

| 되돌린 것 | 나오는 실패 |
| --- | --- |
| 코얼레서 상한 분기를 `throw` → `return compute.get()` | `Expected CoalescerBusyException to be thrown, but nothing was thrown` (8건 중 1건) |
| 아웃박스의 명시 저장 `outbox.save(row)` 삭제 | `★여기가 늘면 '보냈다'가 저장되지 않은 것이다 ... expected: <1> but was: <2>` |
| `restore(prev)` → `clear()`, `c != null ? c : 0L` → `c` | `expected: <10> but was: <null>` 외 5건 |

---

## 1. single-flight — 겹친 요청이 같은 계산을 두 번 돌지 않게

| | |
| --- | --- |
| 문제 | 예열과 사용자 클릭이 같은 원장을 동시에 물어 첫 응답 62.9초(단독 34.9초). 동시 2회는 게이트웨이 504·180초 |
| 결정 | 같은 키(회사·에이전트·원장 지문)의 요청은 최초 요청의 `CompletableFuture` 를 공유 |
| 수치 | 겹친 4개 호출의 계산 4회 → 1회 |
| 코드 | [AgentRunCoalescer.java](src/main/java/com/safeledger/coalescer/AgentRunCoalescer.java) |

**숫자의 경계.** 같은 원장을 다시 물으면 0.1초인데, 그건 결과 캐시가 맞은 시간이지
single-flight 의 효과가 아닙니다. single-flight 가 한 일은 **두 번째 계산을 시작하지 않는 것**입니다.
그 성질은 결과만 봐서는 확인할 수 없습니다. 두 번 계산해도 답은 같아 보이기 때문입니다.

**왜 상한을 넘기면 직접 계산하지 않고 503인가.** 처음에는 직접 계산했습니다. 그러면 같은 원장을
두 번 계산하는 상태로 되돌아갑니다. 막으려던 바로 그것입니다. 늦다는 것은 앞이 죽었다는 뜻이 아니라
부하가 이미 높다는 뜻이고, 거기서 계산을 하나 더 시작하면 둘 다 더 느려집니다.
기다림 상한은 게이트웨이 타임아웃보다 짧아야 합니다. 넘기면 사용자는 원인을 알 수 없는 504 를 봅니다.

**왜 회사 ID 를 키에 넣었나.** 에이전트 이름만으로 묶으면 다른 회사의 계산을 기다리다
남의 결과를 받습니다. 멀티테넌트에서 가장 비싼 종류의 버그입니다.

## 2. 트랜잭셔널 아웃박스 — 그리고 스프링 AOP 자기호출

| | |
| --- | --- |
| 문제 | 외부 전송은 정상(외부에 17건 기록)인데 대기함은 `sent_at=null · attempts=0`. 60초마다 같은 지문을 재전송 |
| 원인 | 자기호출이라 `@Transactional` 이 프록시를 안 타 트랜잭션이 없었고, 엔티티가 detached 라 UPDATE 가 안 나갔다 |
| 결정 | 프록시를 타게 만드는 대신 명시 저장 |
| 코드 | [AnchorOutboxService.java](src/main/java/com/safeledger/outbox/AnchorOutboxService.java) |

```
발급 트랜잭션 ─ 대기함 행 저장 ─ afterCommit 등록 ─ 커밋
                                                    └→ 비동기 전송 1회
                                                       실패하면 60초 스케줄러가 다시
```

`flush()` 의 `@Transactional` 은 프록시를 거쳐 들어올 때만 적용되는데, 호출 경로가 둘 다 같은
객체 안에서 시작했습니다 — `afterCommit → this::flushQuietly` 와 `retry() → flushQuietly()`.

**고친 방식이 "프록시를 타게 만들기" 가 아닙니다.** 리포지토리의 저장은 자체 트랜잭션으로 돌아
주변 트랜잭션 유무와 무관하게 남습니다. 대신 한 번에 여러 행을 보내면 행마다 UPDATE 가 나갑니다.
50건 상한에서는 받아들일 만하다고 판단했습니다.

**발신 측 보장입니다.** 재시도가 만드는 중복은 수신 측 멱등키로 막아야 합니다. 그건 아직입니다.

**이 결함을 테스트로 재현하려면 대기함이 복사본을 돌려줘야 합니다.** 같은 인스턴스를 돌려주면
`row.setSentAt(...)` 이 저장소 안의 객체를 제자리에서 바꿔 버려서, 명시 저장을 지워도 테스트가
초록이 됩니다. 결함을 못 잡는 테스트가 되는 것입니다. 테스트의 `copyOf` 가 그 자리입니다.

## 3. 테넌트 컨텍스트 — "지금 누구의 자료를 보는가"

| | |
| --- | --- |
| 문제 | 중첩 `runAs` 가 끝날 때 해제가 바깥 회사까지 지웠다. 바깥 블록의 남은 쿼리가 테넌트 없이 나간다 |
| 결정 | 이전 값을 저장했다가 finally 로 복원. `runAs(null)` 은 즉시 실패. 미인증은 0 |
| 수치 | 전용 회귀 테스트 0건 → 8건 |
| 코드 | [TenantResolver.java](src/main/java/com/safeledger/tenant/TenantResolver.java) |

**null 을 돌려주면 안 됩니다.** Hibernate 는 null 테넌트를 'root' 로 취급해 **전체 조회**가 됩니다.
미인증 요청 하나가 전 회사 원장을 읽습니다. 그래서 0 으로 접습니다. 0 은 어떤 회사와도 맞지 않으므로
아무것도 안 보입니다. "비어 있음" 을 "전부" 로 읽는 기본값은 이 부류에서 가장 비싼 기본값입니다.

**원 저장소에는 이 클래스 전용 테스트가 없었습니다.** 고쳤다고 말할 수 있는 성질 중 둘에 회귀
테스트가 없다는 것을 이 발췌를 준비하면서 알았고, 그래서 여기서 새로 썼습니다.
8건 중 4건은 한 번씩 실제로 틀렸던 자리입니다.

그리고 이 파일의 주석 한 줄은 제가 **스스로를 정정한** 기록입니다. 원래 "현재 비동기 미사용" 이라고
적혀 있었는데 나중에 사실이 아니게 됐습니다. 없는 상황이라고 적힌 주석은, 그 상황이 생겨도 아무도
안 보게 만듭니다. 그래서 고쳤습니다.

---

## 이 발췌가 증명하지 않는 것

- **코얼레서는 단일 인스턴스 전제입니다.** `ConcurrentHashMap` 은 프로세스 로컬이라 인스턴스가
  2대 이상이면 각자 한 번씩 계산합니다. 분산으로 넓히려면 락만으로는 안 됩니다 — 락은 동시 실행을
  막을 뿐 결과를 공유하지 않습니다. 진행 중 표시 키와 공유 결과 캐시가 같이 있어야 합니다. 아직 안 했습니다.
- **운영 시간 수치는 이 저장소에서 재현되지 않습니다.** 손으로 잰 기록이고, 자동화 스크립트가 없습니다.
- **`runAs` 는 다른 스레드로 전파되지 않습니다.** 한계라서 테스트로 못 박아 두었습니다.
- **원 저장소의 다른 부분은 여기 없습니다.** 특히 문서 위·변조 대조 로직은 지문 계산 방식이 드러나
  가져오지 않았습니다.

## 발췌하면서 바꾼 것

- 패키지 이름, 내부 이슈 번호, 벤더명, 일부 프레임워크 의존(예외 타입을 하나로 좁힘, 아웃박스는
  대조 로직을 떼고 대기함만). **옮긴 메서드의 본문은 바꾸지 않았습니다.**
- 커밋 이력은 가져오지 않았습니다. 파일을 이름으로 하나씩 옮겨 새로 시작했습니다.
- 공개해서는 안 되는 문자열이 남지 않았는지 [`scripts/scan-forbidden.sh`](scripts/scan-forbidden.sh) 가
  검사하고, **push 마다 CI 가 같은 스크립트를 돌립니다**([워크플로](.github/workflows/test.yml)).

  검사 대상은 두 갈래입니다. **값의 모양**(자격증명·접속 문자열·연락처 형식)은 스크립트 안에 두고,
  **고유명사**(거래처·현장·운영 도메인·사내 패키지)는 저장소 밖에 둡니다. 로컬은 `.gitignore` 로
  제외한 `scripts/forbidden-terms.txt`(형식은
  [`forbidden-terms.example.txt`](scripts/forbidden-terms.example.txt)), CI 는 GitHub Secret
  `FORBIDDEN_TERMS` 입니다.

  **가리려는 값을 가리개 안에 적으면 검사는 그것을 보지 못한 채 매번 통과합니다.** 처음에는 반대로
  짰다가 고쳤고, 지금은 스크립트 자신도 검사 대상에 넣습니다.

  ```console
  $ bash scripts/scan-forbidden.sh

  검사 대상: 모양 패턴 14종 · 고유명사 scripts/forbidden-terms.txt (8종)
  ✓ 걸린 항목 없음
  ```

## 라이선스

MIT. 이 저장소에 담긴 파일에만 적용됩니다. [LICENSE](LICENSE) 참조.
