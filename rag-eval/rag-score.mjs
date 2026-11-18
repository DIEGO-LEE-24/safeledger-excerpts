/* RAG 채점표 — 골든 질문셋으로 **재현율과 무응답 정확도**를 점수화한다
 *
 * 왜:
 *   프로젝트 규칙 — "의미검색은 장비·상황 단어가 유사도를 지배한다. 그래서 각도가 다른 조문
 *   (벌칙·기한·사후대응)을 놓치는 **재현율 공백**이 생긴다." 그 공백을 **말로 걱정하는 대신 숫자로 잰다.**
 *   그리고 RAG 철칙("자료 밖이면 응답 없음이 정답")도 반대편에서 함께 재야 의미가 있다 —
 *   무응답을 늘리면 재현율이 떨어지고, 재현율을 올리면 없는 답을 지어낸다. **둘을 같이 본다.**
 *
 * 무엇을 재나:
 *   · answerable  → answered=true 여야 하고(찾았나), expect가 있으면 그 조문이 상위 N에 있어야 한다(맞게 찾았나)
 *   · unanswerable→ answered=false 여야 한다(모르는 걸 모른다고 하나)
 *
 * 쓰는 API는 `/law/search`뿐이다 — **임베딩만 쓰고 생성(LLM)은 안 쓴다.**
 *   그래서 생성 한도가 소진된 날에도 채점이 돌아간다.
 *
 * 사용:  QA_BASE=http://localhost:8081/api QA_TOKEN=<토큰> node rag-eval/rag-score.mjs [질문셋.json]
 *   기본 질문셋 = rag-eval/golden-100.json
 *
 * ※ 발췌하면서 바꾼 것: 원본은 팀 저장소의 로그인 도우미로 토큰을 받았다. 여기서는 그 부분만
 *   환경변수(QA_BASE · QA_TOKEN)로 바꿨다. 조문 대조는 부분 일치에서 정확 일치로 고쳤다(아래 ★ 주석).
 *   검색 API 서버는 이 저장소에 없다.
 */
import fs from 'node:fs';
const BASE = process.env.QA_BASE || 'http://localhost:8081/api';
const SET = process.argv[2] || 'rag-eval/golden-100.json';
const TOP_N = Number(process.env.RAG_TOP_N || 5);
const TOKEN = process.env.QA_TOKEN || '';

const ask = async (q) => {
  const url = `${BASE}/law/search?q=${encodeURIComponent(q)}&topN=${TOP_N}`;
  const r = await fetch(url, { headers: TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {} });
  if (!r.ok) return { error: r.status };
  return r.json();
};

const set = JSON.parse(fs.readFileSync(SET, 'utf8'));
const rows = [];
let hitFound = 0, hitExact = 0, exactAsked = 0, quietOk = 0, errors = 0;
const madeUp = [];   // [엔진, 질문] — 무응답이 정답인데 답한 것
/* ★이 점수를 **어떤 엔진이 냈는지** 함께 기록한다.
   `indexed`는 임베딩·키워드 두 경로 모두 true라 응답만 봐서는 구분되지 않았다 →
   숫자에 엔진을 붙이지 않으면 키워드 점수가 "의미검색 재현율"로 읽힌다. */
/* ★집합(Set)이 **비율을 숨겼다.** 28문항 중 1개만 임베딩이어도 `embedding+keyword`로 찍히고,
   그건 "의미검색을 썼다"로 읽힌다. 실측: 한 세션 안에서 엔진이 실제로 섞였다 —
   캐시된 질의 하나만 임베딩으로 나가고 나머지는 429로 폴백했다(1/5).
   → **문항 수를 센다.** 섞인 실행은 섞였다고, 몇 대 몇인지 숫자로 말한다. */
const modeCount = new Map();

/* basis 접두어 → 색인의 law_name 정본. 못 고르면 null(법령 대조를 강요하지 않는다). */
const LAW_OF = {
  '규칙': '산업안전보건기준에 관한 규칙',
  '안전보건규칙': '산업안전보건기준에 관한 규칙',
  '산안법': '산업안전보건법',
  '시행규칙': '산업안전보건법 시행규칙',
  '시행령': '산업안전보건법 시행령',
};
function expectedLaw(basis) {
  const head = String(basis ?? '').trim().split(/[\s·]/)[0];
  return LAW_OF[head] ?? null;
}
let lawAware = 0;

console.log(`RAG 채점표 · 질문셋 ${SET}`);
console.log(`  answerable ${set.answerable.length} · unanswerable ${set.unanswerable.length} · topN ${TOP_N}\n`);

for (const item of set.answerable) {
  const res = await ask(item.q);
  if (res.error) { errors++; rows.push(['ERR', item.q, `HTTP ${res.error}`]); continue; }
  if (res.mode) modeCount.set(res.mode, (modeCount.get(res.mode) ?? 0) + 1);
  const arts = (res.results ?? []).map((x) => x.articleNo ?? '');
  const pairs = (res.results ?? []).map((x) => [x.lawName ?? '', x.articleNo ?? '']);
  const found = res.answered === true;
  if (found) hitFound++;
  let mark = found ? '찾음' : '무응답';
  if (item.expect?.length) {
    exactAsked++;
    /* ★조문 번호만 보면 **다른 법령의 같은 번호**가 정답으로 찍힌다.
       실측: 기대 조문 41개 중 34개(83%)가 두 개 이상 법령에 같은 번호로 존재한다
       (규칙 제43조 '개구부 등의 방호 조치' vs 법 제43조 '유해위험방지계획서 이행의 확인').
       기대 법령은 `basis` 접두어에서 뽑고, 뽑히지 않는 것(고시·별표·질의회시)만 종전처럼 번호로 센다. */
    const wantLaw = expectedLaw(item.basis);
    if (wantLaw) lawAware++;
    /* ★공개하면서 고친 곳 — 원본은 `a.includes(e)` 였다. 그러면 기대가 `제13조`일 때 `제13조의2`도
       적중으로 센다(과대계상 가능). 여기서는 조문 번호가 정확히 같을 때만 센다.
       README 의 수치는 고치기 전 코드로 잰 기록이다. 그 수치가 이 차이의 영향을 받았는지는
       원본 검색 결과가 남아 있지 않아 확인하지 못했다. */
    const artNo = (x) => (String(x ?? '').replace(/\s+/g, '').match(/제\d+조(?:의\d+)?/) ?? [''])[0];
    const ok = item.expect.some((e) => pairs.some(([ln, a]) =>
      a && artNo(a) === artNo(e) && (!wantLaw || ln === wantLaw)));
    if (ok) hitExact++;
    mark = ok ? '정확' : (found ? '빗나감' : '무응답');
  }
  rows.push([mark, item.q, arts.slice(0, 3).join(', ') || '(결과 없음)']);
}

for (const item of set.unanswerable) {
  const res = await ask(item.q);
  if (res.error) { errors++; rows.push(['ERR', item.q, `HTTP ${res.error}`]); continue; }
  // ★무응답 문항도 같은 엔진 통계에 넣는다(종전엔 answerable만 셌다).
  if (res.mode) modeCount.set(res.mode, (modeCount.get(res.mode) ?? 0) + 1);
  const quiet = res.answered === false;
  if (quiet) quietOk++;
  /* ★지어냄을 **엔진별로 가른다.** 폴백(keyword)이 섞인 판에서 총계만 보면
     의미검색 탓인지 폴백 탓인지 구분이 안 되고, 원인을 잘못 짚으면 엉뚱한 곳을 고친다. */
  if (!quiet) madeUp.push([res.mode ?? "?", item.q]);
  rows.push([quiet ? '침묵' : '★지어냄', item.q,
    quiet ? '' : (res.results ?? []).slice(0, 2).map((x) => `${x.lawName ?? ''} ${x.articleNo ?? ''}`).join(' / ')]);
}

const pct = (a, b) => (b === 0 ? '—' : Math.round((a * 100) / b) + '%');
console.log('  판정  질문 / 상위 조문');
console.log('  ' + '-'.repeat(76));
for (const [mark, q, info] of rows) {
  const flag = mark === '★지어냄' || mark === '빗나감' || mark === 'ERR' ? '❌' : '✅';
  console.log(`  ${flag} ${mark.padEnd(6)} ${q.slice(0, 40).padEnd(42)} ${info.slice(0, 44)}`);
}
console.log('  ' + '-'.repeat(76));
console.log(`  찾음(answered)        ${hitFound}/${set.answerable.length}  ${pct(hitFound, set.answerable.length)}`);
console.log(`  정확(기대 조문 적중)   ${hitExact}/${exactAsked}  ${pct(hitExact, exactAsked)}`);
console.log(`     └ 법령까지 대조한 문항 ${lawAware}/${exactAsked} — 나머지는 조문번호만(고시·별표·질의회시)`);
console.log(`  침묵(무응답이 정답)    ${quietOk}/${set.unanswerable.length}  ${pct(quietOk, set.unanswerable.length)}`);
if (madeUp.length) {
  const byMode = new Map();
  for (const [m] of madeUp) byMode.set(m, (byMode.get(m) ?? 0) + 1);
  console.log(`  ★지어냄 ${madeUp.length}건 — 엔진별 ` + [...byMode].map(([m, n]) => `${m} ${n}`).join(" / "));
}
if (errors) console.log(`  ⚠ 호출 실패 ${errors}건 — 그만큼은 재지 못했다(성적이 아니라 미측정이다)`);

/* ★**어느 엔진의 점수인지 함께 적는다.** 숫자만 옮겨 적으면 다음 사람이 오독한다.
   ⚠ 종전 주석은 원인을 "이 머신엔 임베딩 색인도 API 키도 없다"고 적었는데 **틀렸다** —
     실측: 색인(law_embedding 1,826행)도 키도 있고, 진짜 원인은 질의 임베딩의 **429**다.
   ★엔진을 **비율로** 적는다. 섞인 실행을 `embedding+keyword`로만 적으면
     1/28도 27/28도 같은 글자가 되고, 앞쪽은 "의미검색 성적"으로 읽힌다. */
const total = [...modeCount.values()].reduce((a, b) => a + b, 0);
const embN = modeCount.get('embedding') ?? 0;
const share = total === 0 ? 0 : Math.round((embN * 100) / total);
const mix = total === 0 ? '(알 수 없음)'
  : [...modeCount.entries()].sort((a, b) => b[1] - a[1]).map(([m, n]) => `${m} ${n}`).join(' / ');
console.log(`\n  ▣ 측정 엔진: ${mix}   (의미검색 비중 ${share}%)`);
if (share < 90) {
  console.log(`     ⚠ 이 점수는 **의미검색 재현율이 아니다** — ${total - embN}/${total} 문항이 키워드 폴백으로 나갔다.`);
  console.log('        RAG 성적으로 인용하지 말 것. 임베딩 한도가 회복된 창에서 다시 재야 한다.');
} else if (share < 100) {
  console.log(`     ⚠ 일부(${total - embN}문항)가 폴백이다 — 그만큼 섞인 값이다.`);
}
