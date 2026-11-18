/**
 * 법령 검색 재현율 실측 (2026-08-28)
 *
 * ★ 임계별로 서버를 다시 띄우지 않는다: 임계 0 으로 한 번 돌려 **점수를 전부 받아두면**
 *   0.60/0.64/0.68 의 hit 는 산술로 나온다. 재기동마다 재는 것은 같은 것을 세 번 재는 셈이고,
 *   그 사이 무엇이 달라졌는지 증명할 수 없다.
 *
 * ⚠ 로컬 전용(운영 무접촉). 임베딩 = 로컬 ollama bge-m3(Gemini 호출 0).
 *
 * 사용:  QA_BASE=... QA_TOKEN=... node rag-eval/rag-recall-probe.mjs <질문셋.json> <결과.json> [plain|boost]
 * 검색 API 서버는 이 저장소에 없다. 원본 그대로이며 주석의 내부 호칭만 지웠다.
 */
import fs from 'node:fs';

const BASE = process.env.QA_BASE || 'http://localhost:8099/api';
const GOLDEN = process.argv[2];
const OUT = process.argv[3];
const MODE = process.argv[4] || 'plain';   // plain | boost

/** 프로젝트 규칙 — 장비·상황 단어가 유사도를 지배해 「각도가 다른 조문」을 놓친다.
 *  질의에 그 각도의 어휘를 얹어 본다. ★새 뜻을 넣지 않는다 — 물음의 축을 말로 적을 뿐이다. */
const BOOST = [
  [/벌칙|처벌|과태료|벌금|징역/, '벌칙 과태료 벌금 징역 처벌'],
  [/기한|며칠|기간|이내|보고.*언제|언제.*보고/, '기한 이내 지체 없이 보고 기간'],
  [/사고|재해|중대재해|발생.*조치|사후/, '발생 시 조치 작업중지 보고 사후 대응'],
  [/무엇|정의|뜻|이란|의미/, '정의 용어의 뜻'],
  [/누가|책임|의무자|사업주|도급인/, '사업주 도급인 의무 책임 주체'],
  [/보존|기록.*보관|몇 년/, '기록 보존 보관 기간 년'],
  [/교육|훈련/, '안전보건교육 실시 시간 대상'],
  [/신고|제출|서류/, '신고 제출 서식 관서'],
];

function boost(q) {
  const add = [];
  for (const [re, words] of BOOST) if (re.test(q)) add.push(words);
  return add.length ? `${q} ${add.join(' ')}` : q;
}

/** 응답에서 조문 번호를 모은다 — 표기 편차가 커서 「제N조」까지만 본다(항·호는 무시). */
function articles(text) {
  return [...String(text ?? '').matchAll(/제\s?(\d+)\s?조(?:의\s?(\d+))?/g)]
    .map(m => `제${m[1]}조${m[2] ? `의${m[2]}` : ''}`);
}

function norm(a) { return String(a).replace(/\s+/g, ''); }

const TOKEN = process.env.QA_TOKEN || '';
const TOPN = process.env.TOPN || '5';

/* 계약은 소스에서 읽었다: GET /law/search?q=&topN= · 응답 results[].{articleNo,score,lawName}.
   ⚠ POST 로 짐작해 부르면 403 이 오고, 그걸 "0건"으로 읽으면 재현율이 0으로 보인다. */
async function search(query) {
  const u = `${BASE}/law/search?q=${encodeURIComponent(query)}&topN=${TOPN}`;
  const r = await fetch(u, { headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {} });
  if (!r.ok) return { error: `${r.status}` };
  return r.json();
}

const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));
const rows = [];

for (const [i, item] of golden.answerable.entries()) {
  const q = MODE === 'boost' ? boost(item.q) : item.q;
  let res;
  try { res = await search(q); } catch (e) { res = { error: String(e.message ?? e) }; }
  const hits = (res.results ?? []).map(h => ({ article: h.articleNo ?? '', score: h.score ?? null, law: h.lawName ?? '' }));
  const want = (item.expect ?? []).map(norm);
  /** 정답 조문이 top5 안에 있는가 — 그 행의 점수가 「이 문항이 살아나는 임계」다. */
  const matched = hits.filter(h => want.includes(norm(h.article)));
  rows.push({
    n: i + 1,
    q: item.q,
    sent: q,
    expect: item.expect,
    basis: item.basis ?? '',
    top: hits[0]?.score ?? null,
    hitScore: matched.length ? Math.max(...matched.map(m => m.score ?? 0)) : null,
    hitArticle: matched[0]?.article ?? null,
    got: hits.map(h => `${h.article}@${(h.score ?? 0).toFixed(3)}`),
    error: res.error ?? null,
  });
  process.stdout.write(`${i + 1} `);
}

fs.writeFileSync(OUT, JSON.stringify({ mode: MODE, base: BASE, rows }, null, 2), 'utf8');
console.log(`\n${MODE}: ${rows.length}문 → ${OUT}`);
