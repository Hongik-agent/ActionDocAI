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

Vercel Root Directory는 `web`입니다. AI를 활성화하려면 Supabase 프로젝트에서 GitHub OAuth 공급자를 설정하고 사이트 URL을 배포 도메인으로 등록한 뒤, SQL Editor에서 [schema.sql](server/schema.sql)을 실행하세요. Vercel 서버 환경 변수에 `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `OPENAI_API_KEY`를 등록하고 다시 배포합니다. 키 없이도 OCR과 기본 정리를 사용할 수 있습니다.

AI는 10쪽·10,000자까지 지원합니다. 기본 모델은 gpt-4.1-mini이며 OPENAI_MODEL로 변경합니다. USER_DAILY_LIMIT(기본 3), GLOBAL_DAILY_LIMIT(기본 30)은 한국 시간 기준 **분석 시도 횟수**입니다. 한 시도에서 요약 및 검증을 위해 최대 두 번 모델을 호출합니다. 실패한 시도도 운영비 보호를 위해 예약 횟수에 포함됩니다. 한도 초과·저장소 장애·검증 실패 시 기본 정리를 제공합니다.

캐시는 계정·본문·쪽 위치·목적·모델·검증 버전에 따라 구분합니다. 7일이 지난 기록은 다음 예약 요청에서 정리하며, 유효한 캐시 조회는 한도 차감 없이 가능합니다. 운영자는 장기 미사용 시에도 물리적 삭제가 필요하면 만료 기록 삭제를 주기적으로 실행하세요. 서비스 역할 키와 OpenAI 키는 브라우저 설정이나 번들에 넣지 않습니다.
