#!/usr/bin/env bash
# 공개해서는 안 되는 문자열이 저장소에 남아 있는지 검사한다.
#
# 이 저장소는 비공개 저장소에서 파일을 골라 옮겨 만들었다. 옮기는 손이 한 번만 미끄러지면
# 자격증명이나 거래처 정보가 공개된다. 그래서 그 검사를 사람의 기억이 아니라 여기에 둔다.
#
# ★설계에서 한 번 틀렸던 것 — 기록해 둔다.
#   처음에는 막으려는 고유명사를 이 파일 안에 그대로 적어 두고, 이 파일만 검사에서 제외했다.
#   그러면 **가리려던 값이 가리개 안에 평문으로 남고**, 검사는 그것을 보지 못한 채 매번 통과한다.
#   검사가 있다는 사실이 검사가 없는 것보다 나쁘게 작동한다.
#   그래서 지금은 (1) 고유명사를 저장소 밖에 두고 (2) 이 파일도 검사 대상에 포함한다.
#
# 검사 대상 패턴은 두 갈래다.
#   ① 아래 SHAPES — 값의 '모양'만 보는 일반 패턴. 저장소에 남아도 아무것도 드러내지 않는다.
#   ② 고유명사 — 이 저장소에 두지 않는다.
#        로컬: scripts/forbidden-terms.txt  (.gitignore 로 제외, 형식은 .example 참고)
#        CI  : 환경변수 FORBIDDEN_TERMS     (GitHub Secret 에서 주입)
#      둘 다 없으면 ①만 검사하고, 그 사실을 출력에 밝힌다. 조용히 넘어가지 않는다.
#
#   이 파일 자신은 이렇게 다룬다.
#     ① 모양 패턴은 이 파일을 건너뛴다. 패턴은 값이 아니라 값의 생김새라, 여기 남아도
#        아무것도 드러내지 않는다. 건너뛰지 않으면 SHAPES 에 적은 낱말이 자기 자신에
#        걸려 검사가 늘 실패한다.
#     ② 고유명사는 이 파일도 검사한다. 실수로 여기에 적어 넣는 것이 바로 처음에
#        저지른 잘못이기 때문이다.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

SHAPES=(
  # 자격증명의 모양
  'POSTGRES_PASSWORD' 'JWT_SECRET' 'PRIVATE KEY'
  '(password|passwd|secret|token|api[-_]?key)[\"'"'"']?\s*[:=]\s*[\"'"'"'][^\"'"'"']{6,}'
  'Bearer [A-Za-z0-9._-]{20,}'
  'AKIA[0-9A-Z]{16}'                      # AWS 액세스 키
  'gh[pousr]_[A-Za-z0-9]{30,}'            # GitHub 토큰
  'sk-[A-Za-z0-9]{20,}'                   # 흔한 API 키 형식
  'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.'   # JWT
  # 접속 문자열
  '(postgres|postgresql|mysql|mongodb|redis|amqp)://[^\s\"'"'"']*:[^\s\"'"'"']*@'
  # 사설·공인 주소 (루프백과 예약 도메인은 뺀다 — 테스트가 쓴다)
  'https?://(?!127\.0\.0\.1|localhost)[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}'
  # 개인 식별자 (문서용 예약 도메인 RFC 2606 과 저장소 주인 연락처는 뺀다)
  '[a-z0-9._-]+@(?!example\.(com|org))[a-z0-9.-]+\.(kr|com|net|org)'
  '01[016789]-?[0-9]{3,4}-?[0-9]{4}'
  '[0-9]{6}-[1-4][0-9]{6}'
)

OWNER_EMAIL='diegolee5494@gmail.com'

# ── 고유명사 목록 읽기 (저장소 밖)
TERMS=()
TERMS_SRC="(없음)"
if [ -n "${FORBIDDEN_TERMS:-}" ]; then
  while IFS= read -r t; do [ -n "$t" ] && TERMS+=("$t"); done <<< "$FORBIDDEN_TERMS"
  TERMS_SRC="환경변수 FORBIDDEN_TERMS (${#TERMS[@]}종)"
elif [ -f "$HERE/forbidden-terms.txt" ]; then
  while IFS= read -r t; do
    t="${t%%#*}"; t="$(echo "$t" | sed 's/[[:space:]]*$//')"
    [ -n "$t" ] && TERMS+=("$t")
  done < "$HERE/forbidden-terms.txt"
  TERMS_SRC="scripts/forbidden-terms.txt (${#TERMS[@]}종)"
fi

FAIL=0
scan() {   # $1=패턴  $2=분류
  local hits skip_self=()
  # 모양 패턴만 이 파일을 건너뛴다. 고유명사는 이 파일도 본다 — 머리말 참고.
  [ "$2" = "모양" ] && skip_self=(--exclude=scan-forbidden.sh)
  hits=$(grep -rInP --binary-files=without-match \
           --exclude-dir=.git --exclude-dir=target \
           "${skip_self[@]}" \
           --exclude=forbidden-terms.txt --exclude=forbidden-terms.example.txt \
           -- "$1" "$ROOT" 2>/dev/null | grep -vF -- "$OWNER_EMAIL" || true)
  if [ -n "$hits" ]; then
    # 고유명사는 화면에 다시 찍지 않는다 — 로그가 또 하나의 유출 경로가 된다.
    if [ "$2" = "고유명사" ]; then
      echo "✗ 고유명사 목록의 항목이 걸렸다 (값은 출력하지 않는다)"
      echo "$hits" | sed 's/:.*//' | sort -u | sed 's/^/    /'
    else
      echo "✗ 걸림: $1"
      echo "$hits" | sed 's/^/    /'
    fi
    FAIL=1
  fi
}

for p in "${SHAPES[@]}"; do scan "$p" "모양"; done
for t in "${TERMS[@]}"; do scan "$t" "고유명사"; done

echo
echo "검사 대상: 모양 패턴 ${#SHAPES[@]}종 · 고유명사 ${TERMS_SRC}"
if [ ${#TERMS[@]} -eq 0 ]; then
  echo "⚠ 고유명사 목록이 없어 모양 패턴만 검사했다."
  echo "  로컬에서는 scripts/forbidden-terms.txt 를 두고, CI 에서는 FORBIDDEN_TERMS 를 주입할 것."
fi
[ "$FAIL" -eq 0 ] && { echo "✓ 걸린 항목 없음"; exit 0; }
echo
echo "공개 전에 위 항목을 지워야 한다."
exit 1
