# ActionDoc Web · 무료 기본 정리

로그인·API 키 없이 브라우저에서 문서를 읽고, 목적별로 원문을 발췌하는 웹 기본판입니다. 기존 Mac 앱과 Java 서버는 수정하지 않습니다.

## 지원 범위

| 항목 | 현재 제공 |
| --- | --- |
| 파일 | 텍스트 PDF, HWP 5, HTML, TXT, MD |
| 제한 | 파일 50MB, PDF 50쪽, 추출 본문 160,000자 |
| HWP | 본문 텍스트 추출, 본문 압축 해제 최대 32MB. 암호·배포용·DRM·인증서 보호 문서 제외 |
| 목적 | 전체 / 공문·업무 요청 / 지원사업·모집 공고 / 요금·계약 안내 |
| 근거 | 추출 문단 ID, PDF 쪽 번호, 전체 관련 원문 펼치기 |
| 저장 | 사용자 요청 시 마크다운 파일 다운로드 |
| 데이터 처리 | 문서는 브라우저 메모리에서 처리하며 서버 업로드·로컬 저장소 저장 없음 |

원문 문장을 바꾸거나 숫자를 추측하지 않습니다. 긴 문장은 예외가 잘리지 않도록 미리보기에서 제외하고 전체 근거에 보존합니다. 이것은 생성형 AI 요약이나 자격 판단이 아닙니다. 키워드 추출은 관련 내용을 누락하거나 무관한 내용을 포함할 수 있습니다.

웹판은 HWPX, 수식·그림 해석, 원본 표 배치 보존, 자동 후속 작업 등록을 지원하지 않습니다. HWP는 실제 쪽수를 알 수 없으므로 PDF의 50쪽 제한을 적용하지 않습니다. 텍스트를 읽지 못한 PDF 쪽은 경고로 표시하고 OCR 후에도 읽을 본문이 없으면 안내합니다. 큰 이미지가 포함된 쪽도 OCR 대상입니다. 스캔 PDF의 OCR 인식 결과는 원본과 대조하세요.

## 로컬 실행

Node.js 20 이상을 사용합니다.

```bash
cd web
npm ci
npm run build
npm run dev
```

http://127.0.0.1:4173 에서 확인합니다. 화면을 변경하면 다시 빌드하세요. 문서를 여는 동안 네트워크를 통한 문서 전송은 없으며, PDF/HWP 라이브러리도 웹사이트와 같은 출처에서 제공합니다. 사이트 접속 자체에는 일반적인 호스팅 접속 기록이 남을 수 있습니다.

## Vercel 배포

1. Vercel에서 **Add New → Project**로 `Hongik-agent/ActionDocAI` 저장소를 가져옵니다.
2. **Root Directory**를 **web**으로 선택합니다. 저장소 루트의 Java 앱을 빌드하지 않습니다.
3. Framework Preset은 **Other**, Build Command는 `npm run build`, Output Directory는 `dist`, Install Command는 `npm ci`입니다. `web/vercel.json`에도 지정되어 있습니다.
4. API 키·데이터베이스·로그인 환경 변수 없이 배포할 수 있습니다.

무료 Hobby의 개인·비상업 용도 및 사용량 제한은 Vercel의 최신 정책을 확인하세요: https://vercel.com/docs/plans/hobby

## 검증

```bash
npm test
npm run test:browser
```

브라우저 검증은 기본적으로 macOS의 Google Chrome을 사용합니다. 다른 OS에서는 `CHROME_PATH`에 Chrome/Chromium 실행 파일을 지정합니다. 테스트는 로컬 서버를 자동 실행·종료하며 합성 PDF/HWP만 사용합니다. 50/51쪽 경계, 혼합 PDF 경고, HTML 스크립트 비실행, 외부 요청 없음, 마크다운 저장과 모바일 레이아웃을 확인합니다.

공개 HWP 실물 검증은 `ACTIONDOC_HWP_FIXTURE` 환경 변수에 국민건강정보자료 규정안 파일 경로를 지정한 뒤 `npm test`로 실행할 수 있습니다. 파일 자체를 저장소에 넣지 않습니다.

## 웹 OCR 및 무료 AI 운영 설정

스캔 PDF는 브라우저의 Tesseract 한국어·영어 모델로 읽습니다. 모델 파일은 배포 사이트에서 받아오며 OCR 이미지는 브라우저에서 처리합니다. OCR 결과는 인식 쪽을 표시합니다.

Vercel Root Directory는 `web`입니다. AI를 활성화하려면 Supabase 프로젝트에서 Google OAuth 공급자를 설정하고 사이트 URL을 배포 도메인으로 등록한 뒤, SQL Editor에서 [schema.sql](server/schema.sql), [retention.sql](server/retention.sql) 순서로 실행하세요. 기존 프로젝트도 두 파일을 적용한 후 웹을 배포하세요. Supabase Cron에서 `actiondoc-expire-jobs`가 매시간 성공하는지 확인하세요. 공개 운영 전 운영자 문의처, 실제 이전 국가·수탁자 연락처·보관 기간과 국외 이전 근거를 확인하고 개인정보 처리방침에 반영하세요. Vercel 서버 환경 변수에 `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `GEMINI_API_KEY`(또는 `OPENAI_API_KEY`)를 등록하고 다시 배포합니다. 키 없이도 OCR과 기본 정리를 사용할 수 있습니다.

AI는 10쪽·10,000자까지 지원합니다. Gemini 키가 있으면 gemini-3.1-flash-lite를 사용하며 GEMINI_MODEL로 변경합니다. OpenAI는 OPENAI_MODEL로 설정합니다. Vercel에서 운영 키는 Production 환경의 Sensitive 변수로 저장하세요. USER_DAILY_LIMIT(기본 3), GLOBAL_DAILY_LIMIT(기본 30)은 한국 시간 기준 **분석 시도 횟수**입니다. 한 시도는 요약과 검증 두 단계입니다. Gemini의 500·502·503·504 응답은 각 단계에서 1~1.5초 뒤 최대 1회만 재시도합니다(전체 최대 4회 호출). 각 단계는 재시도 대기를 포함해 최대 45초이며 429·설정 오류·네트워크 오류·검증 실패는 자동 재시도하지 않습니다. 재시도는 기존 예약 안에서 처리해 분석 시도 횟수를 추가 차감하지 않습니다. 실패한 시도도 운영비 보호를 위해 예약 횟수에 포함됩니다. 서비스 전체 사용량은 계정·문서 정보가 없는 날짜별 집계에 저장하므로 탈퇴해도 줄어들지 않습니다. 기존 프로젝트는 schema.sql을 다시 실행하면 남아 있는 요청 기록으로 집계를 초기화하며, 재실행해도 중복 합산하거나 기존 집계를 낮추지 않습니다. 이미 삭제된 과거 요청은 복원할 수 없습니다. 한도 초과·저장소 장애·검증 실패 시 기본 정리를 제공합니다.

캐시는 계정·본문·쪽 위치·목적·모델·검증 버전에 따라 구분합니다. 캐시에는 요약과 근거 위치만 저장하고, 같은 문서에서 인용문을 복원합니다. 만료 기록은 요청이 없어도 매시간 정리합니다. 탈퇴하면 해당 계정에 연결된 기록이 함께 삭제됩니다. 서비스 역할 키와 AI 공급자 키는 브라우저 설정이나 번들에 넣지 않습니다.

### Google 로그인 연결

Google Cloud OAuth 웹 애플리케이션의 JavaScript 원본은 `https://actiondoc-ai.vercel.app`, 리디렉션 URI는 `https://uvmadeilnxdjxmwvhqsr.supabase.co/auth/v1/callback`입니다. 클라이언트 ID와 Secret은 Supabase Authentication → Sign In / Providers → Google에 등록합니다. Secret은 브라우저 코드나 Git에 넣지 않습니다. 실제 Google 로그인이 완료되는 것을 확인한 후 GitHub 공급자를 비활성화합니다. 기존 계정과 기록은 삭제하지 않습니다.
