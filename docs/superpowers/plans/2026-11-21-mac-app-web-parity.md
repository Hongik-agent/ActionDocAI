# Mac 앱 웹 개선 사항 반영 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 웹(`web/`)에서 개선한 PDF 읽기, 기본 정리, AI 분석, 하드코딩 정리를 Mac 앱(Java 서버 `src/` + Swift `desktop/`)에 반영하고, 읽기 취소와 분석 결과 마크다운 저장을 추가한다.

**Architecture:** 병렬 OCR(worker 2개)과 8분 제한은 Mac 앱에 이미 있으므로 다루지 않는다. 웹 구현을 기준으로 삼아 Java로 옮기되, 두 구현이 같은 동작을 하는지는 `shared/fixtures/*.json`을 웹 테스트(`node --test`)와 Java 테스트(JUnit)가 함께 읽어 검증한다. Mac 앱만의 기능(개인 상황 비교, 연결 주소, 작업 등록, 기록)은 유지한다. 네 단계(C → A → B → D)는 각각 따로 테스트하고 앱을 빌드할 수 있다.

**Tech Stack:** Java 17, Spring Boot 3.2, PDFBox 3.0.3, Jackson, JUnit 5 + Mockito / Swift 5 (SwiftUI, macOS 14+) / Node 20 `node:test`

**Spec:** 별도 spec 문서 없음. 아래 "결정 사항"이 spec이다. 웹 기준 구현: `web/src/summary.js`, `web/src/read.js`, `web/server/verify.js`.

## 결정 사항

1. 범위는 C(AI 분석), A(PDF 읽기), B(기본 정리), D(Java 하드코딩 점검) 전부이며 이 순서로 진행한다.
2. 기본 정리(AI를 쓰지 않는 로컬 분석)는 **웹과 완전히 동일한 결과**를 낸다. `NoticeReport`의 공고문 전용 정리(제목, 신청 기간 종료 배너, 개요)와 `SummaryPurpose`는 없앤다.
3. 문서 읽기 취소 버튼을 추가한다.
4. Mac의 "마크다운 저장"에 분석 결과(개요, 섹션, 근거, 내 상황 비교, 할 일)를 포함한다.
5. Mac의 AI는 계속 사용자 개인 OpenAI 키를 쓴다. 3단계 구조(사실 추출 → 보고서 구성 → 항목별 감사)는 유지한다.

## Global Constraints

- 새 의존성을 추가하지 않는다 (`pom.xml`, `web/package.json` 변경 없음).
- 제한값은 그대로: 파일 50MB, PDF 50쪽, 본문 160,000자.
- 특정 문서 하나에 맞춘 문자열·규칙을 넣지 않는다. 테스트에는 실제 문서 문자열을 복사하지 않고 같은 유형의 합성 문자열을 쓴다.
- 로그에는 원문·요약 내용을 남기지 않는다 (단계, 시간, 개수, 위치, 숫자를 `N`으로 가린 단위만).
- 저장된 기록(`records.json`)의 이전 보고서 형식이 계속 열려야 한다. Swift 모델에 새 필드를 추가하면 optional로 둔다.
- 빌드: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home PATH="$JAVA_HOME/bin:$PATH" ./desktop/build.command` (Maven 테스트와 `--self-test` 포함).
- Java 테스트만: `JAVA_HOME=… ~/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn -q test -Dtest=<Class>`
- 웹 테스트: `cd web && npm test`
- 커밋: 한국어 메시지, `git -c user.name="보민" -c user.email="347179590+bo-min-code@users.noreply.github.com" commit`, Claude 표기(`Co-Authored-By` 등) 금지.

## Review Focus

1. **HWP·TXT 입력(쪽 번호 0)으로 기본 정리** — 위치가 "본문"으로 표시되고 결과가 웹과 같아야 한다. → Task 8 fixture에 `page: 0` 사례 포함.
2. **회전된 PDF 쪽의 이미지 영역 OCR** — `/Rotate 90` 쪽에서 잘라낸 영역이 실제 이미지 위치와 맞아야 한다. → Task 6 테스트 `rotatedPageCropsTheImageRegion`.
3. **AI가 다른 구간의 블록 ID를 근거로 냄** — 그 사실은 제외되고 경고가 남아야 한다. → Task 2 테스트 `factCitingBlockOutsideItsPartIsDropped`.
4. **OCR 도중 취소** — `actiondoc-ocr` 프로세스가 남지 않고, 바로 다음 문서를 정상적으로 읽어야 한다. → Task 7 테스트 `cancelDuringOcrStopsWorkersAndNextReadWorks`.
5. **이전 기록 열기** — 오늘 이전 형식의 `records.json`(근거 `quote`가 짧은 인용문인 보고서)이 열리고 마크다운 저장도 된다. → Task 10 자체 테스트.

---

## 단계 C: AI 분석

### Task 1: 값 기반 숫자 검증을 웹과 공유

**Files:**
- Create: `shared/fixtures/numbers.json`
- Create: `src/main/java/ai/actiondoc/NumberCheck.java`
- Create: `src/test/java/ai/actiondoc/NumberCheckTests.java`
- Modify: `web/tests/ai.test.mjs` (인라인 사례 → fixture 읽기)
- Modify: `src/main/java/ai/actiondoc/LlmDocumentAnalyzer.java:102-104` (`numbersSupported`, `units`, `numbers` 교체·삭제)

**Interfaces:**
- Produces: `record NumberCheck.Token(String key, String unit)`, `static List<Token> NumberCheck.tokens(String text)`, `static List<Token> NumberCheck.missing(String statement, String sourceText)`, `static boolean NumberCheck.supported(String statement, List<Evidence> evidence)` (근거 `quote`를 `\n`으로 이어 `missing`이 비었는지)

- [ ] **Step 1: fixture 작성** — 형식 `{"accept":[{"sources":["…"],"statement":"…"}],"reject":[…]}`. `web/tests/ai.test.mjs`의 `amount verification preserves magnitude…`(거부 10, 통과 7)와 `numbers compare by value and attached unit…`(통과 5, 거부 3) 사례를 그대로 옮긴다.
- [ ] **Step 2: 웹 테스트가 fixture를 읽도록 변경** — 두 테스트의 인라인 배열을 `JSON.parse(await readFile(new URL('../../shared/fixtures/numbers.json', import.meta.url)))`로 바꾸고 `checkEvidence([{indexes: sources.map((_,i)=>i), statement}], sources.map(text=>({text})))`로 검사. Run: `cd web && npm test` → PASS (동작 변화 없음).
- [ ] **Step 3: 실패하는 Java 테스트** — `NumberCheckTests.sharedFixtureMatchesWeb`: fixture를 `Path.of("shared/fixtures/numbers.json")`에서 읽어 accept는 `missing(statement, String.join("\n", sources))`가 비어 있음, reject는 비어 있지 않음을 단언. Run: mvn `-Dtest=NumberCheckTests` → FAIL (클래스 없음).
- [ ] **Step 4: `NumberCheck` 구현** — `web/server/verify.js`의 `numbers()`를 그대로 옮긴다: `Normalizer.normalize(NFKC)`, 숫자 정규식 `\d{1,3}(?:,\d{3})+(?!\d)|\d+(?:\.\d+)*`, 점 2개 이상은 정수 여러 개, 십·백·천 / 만·억·조 배율과 연쇄(`1억 5천만`), 앞 `\p{Sc}` 통화, 앞 `-`/`−` 부호(앞 글자가 문자·숫자가 아닐 때), 뒤 첫 글자가 `\p{L}` 또는 `%`이면 단위. 비교는 key 같고 (단위가 한쪽이라도 비었거나 같음).
- [ ] **Step 5: 테스트 통과 확인** — Run: mvn `-Dtest=NumberCheckTests` → PASS.
- [ ] **Step 6: `LlmDocumentAnalyzer` 교체** — `numbersSupported(...)` 호출 4곳을 `NumberCheck.supported(...)`로 바꾸고 `numbersSupported`, `units`, `numbers` 메서드를 삭제. Run: mvn `-Dtest=AiProviderTests` → PASS.
- [ ] **Step 7: 커밋** — `숫자 검증을 값 기반으로 바꾸고 웹과 같은 사례로 검증`

### Task 2: AI 근거를 블록 ID로 받기

**Files:**
- Modify: `src/main/java/ai/actiondoc/LlmDocumentAnalyzer.java` (`EXTRACT`, `FACT_SCHEMA`, `sourceEvidence`, 추출 루프 22-31행)
- Test: `src/test/java/ai/actiondoc/AiProviderTests.java`

**Interfaces:**
- Consumes: `NumberCheck.supported` (Task 1)
- Produces: 추출 응답 형식 `{"facts":[{"category","topic","statement","blockIds":[string]}]}`; `Evidence(blockId, page, quote)`의 `quote`는 **블록 원문 전체**

- [ ] **Step 1: 실패하는 테스트 2개** — `AiProviderTests.extraction()`의 사실을 `blockIds`로 바꾸고:
  - `factEvidenceIsTheCitedBlockText`: `blockIds:["b2"]` → 해당 사실의 근거 `quote`가 `document().blocks().get(1).text()`와 같음.
  - `factCitingBlockOutsideItsPartIsDropped` (Review Focus 3): 블록이 2구간으로 나뉘는 문서(`partition` 경계를 넘는 길이)에서 1구간 응답이 2구간 블록 ID를 인용 → 그 사실은 `facts()`에 없고 `warnings()`에 "원문 근거를 검증하지 못한" 경고가 있음.
  Run → FAIL.
- [ ] **Step 2: 구현** — 스키마를 구간마다 만든다: `blockIds`는 `{"type":"array","items":{"type":"string","enum":[이 구간의 블록 ID들]},"minItems":1}`. `sourceEvidence(JsonNode ids, List<Block> part)`는 ID를 중복 제거해 구간 안 블록만 `Evidence(id, page, block.text())`로 만들고, 하나라도 구간 밖이면 빈 목록. `EXTRACT`에서 "quote는 … 연속 문자열" 문장을 "각 사실을 직접 뒷받침하는 블록의 id를 blockIds에 넣는다. 원문을 다시 쓰지 않는다."로 바꾼다.
- [ ] **Step 3: 테스트 통과 확인** — Run: mvn `-Dtest=AiProviderTests` → PASS.
- [ ] **Step 4: 커밋** — `AI 사실 추출의 근거를 블록 ID로 받아 원문을 서버가 직접 사용`

### Task 3: 보고서의 고정 틀과 개수 상한 제거

**Files:**
- Modify: `src/main/java/ai/actiondoc/LlmDocumentAnalyzer.java` (`EXTRACT`, `SYNTHESIZE`, 24·32·36행 개수 검사)
- Test: `src/test/java/ai/actiondoc/AiProviderTests.java`

- [ ] **Step 1: 실패하는 테스트** — `reportShapeIsLeftToTheModel`: 보고서 응답에 섹션 9개, 할 일 6개, 사실 101개를 넣어도 `invalid()` 예외 없이 분석이 끝나고 검증을 통과한 섹션 수가 9개. Run → FAIL.
- [ ] **Step 2: 구현** — 프롬프트에서 "최대 100개", "2~3문장", "최대 7개", "2~4개의 짧은 문장", "최대 5개"를 지우고 웹 문구로 바꾼다: "섹션과 항목의 구성·개수는 문서 구조와 분량에 맞게 스스로 정한다. 문서에 실제로 있는 중요한 내용은 빠뜨리지 말고, 같은 내용은 반복하지 않는다." 코드의 개수 검사(`facts>100`, `facts>240`, `sections>8`, `personalChecks>20`, `actions>5`)를 지우고 배열 여부 검사만 남긴다. `max_output_tokens` 14000에는 "폭주 방지 상한이며 목표 분량이 아니다" 주석.
- [ ] **Step 3: 테스트 통과 확인** — Run: mvn `-Dtest=AiProviderTests` → PASS.
- [ ] **Step 4: 커밋** — `AI 보고서 구성과 항목 수를 문서에 맞게 AI가 정하도록 변경`

### Task 4: 분석 전체가 하나의 마감 시각을 나눠 쓰기

**Files:**
- Modify: `src/main/java/ai/actiondoc/LlmDocumentAnalyzer.java` (`analyze`, `call`)
- Test: `src/test/java/ai/actiondoc/AiProviderTests.java`

**Interfaces:**
- Produces: `static final Duration ANALYSIS_BUDGET = Duration.ofSeconds(1440)` — `desktop/Sources/Service.swift`의 `/api/analyses` 요청 제한 1500초에서 응답 여유 60초를 뺀 값(주석으로 연결). `call(String name, String instructions, Object input, String schema, Instant deadline)`

- [ ] **Step 1: 실패하는 테스트** — `callsShareOneDeadline`: Mockito로 `HttpClient.send`가 받은 `HttpRequest.timeout()`을 기록. 첫 호출의 제한이 `ANALYSIS_BUDGET` 이하이고, 각 호출의 제한이 이전 호출보다 짧거나 같음(150초 고정이 아님). Run → FAIL.
- [ ] **Step 2: 구현** — `analyze` 시작 시 `deadline = Instant.now().plus(ANALYSIS_BUDGET)`; `call`의 `.timeout(...)`은 `Duration.between(now, deadline)`(최소 1초). 응답마다 `LoggerFactory.getLogger(LlmDocumentAnalyzer.class).info("AI call {}", …)`로 단계 이름, 걸린 ms, `usage.input_tokens`, `usage.output_tokens`, `usage.output_tokens_details.reasoning_tokens`만 기록.
- [ ] **Step 3: 테스트 통과 확인** — Run: mvn `-Dtest=AiProviderTests` → PASS.
- [ ] **Step 4: 단계 C 빌드와 커밋** — 빌드 명령(Global Constraints) → `DESKTOP_SELF_TEST_PASSED`. 커밋 `AI 분석 호출이 하나의 마감 시각을 나눠 쓰고 시간과 토큰을 기록`

## 단계 A: PDF 읽기

### Task 5: PDF 줄 합치기를 웹과 공유

**Files:**
- Create: `shared/fixtures/line-joining.json`
- Modify: `web/src/summary.js` (`pdfPageText`를 `pdfLines(items)` + `joinLines(lines)`로 분리, 둘 다 export)
- Modify: `web/tests/summary.test.mjs` (`PDF text keeps real word gaps…`의 줄 합치기 단언을 fixture로)
- Create: `src/main/java/ai/actiondoc/PdfLines.java`
- Create: `src/main/java/ai/actiondoc/LayoutTextStripper.java`
- Modify: `src/main/java/ai/actiondoc/PdfPagePipeline.java:41-42` (`PDFTextStripper` → `LayoutTextStripper`)
- Create: `src/test/java/ai/actiondoc/PdfLinesTests.java`

**Interfaces:**
- Produces (웹): `export function joinLines(lines: {text,left,right,gap}[]): string`, `export function pdfLines(items): line[]`, `pdfPageText = items => joinLines(pdfLines(items))`
- Produces (Java): `record PdfLines.Line(String text, double left, double right, double gap)` (gap은 글자 크기 대비 최대 가로 간격), `static String PdfLines.join(List<Line> lines)`; `LayoutTextStripper.lines(PDDocument pdf, int page) -> List<PdfLines.Line>`

- [ ] **Step 1: fixture 작성** — `{"cases":[{"name","lines":[{"text","left","right","gap"}],"expected":["…"]}]}`. 사례: 오른쪽 끝까지 찬 줄 + 다음 줄 이어짐(합침), 문장 끝 `다.`(합치지 않음), 목록 기호로 시작하는 다음 줄(합치지 않음), 글자 간격 벌린 제목 `사 업 명:`(붙이고 다음 줄과 합치지 않음), 목차 줄(제목 … gap 2 이상 … 쪽 번호, 합치지 않음), 표 행(gap 2 이상, 합치지 않음).
- [ ] **Step 2: 웹 분리와 테스트 전환** — `joinLines`로 fixture 사례 검사, 기존 `pdfPageText` 테스트의 글자 간격 단언은 유지. Run: `cd web && npm test` → PASS.
- [ ] **Step 3: 실패하는 Java 테스트** — `PdfLinesTests.sharedFixtureMatchesWeb` (fixture 전 사례를 `PdfLines.join`으로 비교), `strippedPdfRejoinsWrappedParagraph` (PDFBox로 만든 PDF: 쪽 너비를 채운 두 줄 문단 + 오른쪽 끝 쪽 번호가 있는 목차 줄 → 문단은 한 줄, 목차 줄은 따로). Run → FAIL.
- [ ] **Step 4: 구현** — `PdfLines.join`은 `web/src/summary.js`의 `joinLines`를 그대로 옮긴다(`listMarker` 정규식, 글자 간격 제목 정규식, 오른쪽 여백 0.12 비율, gap < 2 조건, 문장 끝 `[.!?。]$|다$`). `LayoutTextStripper`는 `PDFTextStripper`를 상속해 `writeString(String, List<TextPosition>)`에서 줄의 텍스트(PDFBox가 넣은 공백 포함), `left`=최소 x, `right`=최대 x+너비, `gap`=연속 글자 사이 최대 간격 ÷ 글자 크기를 모으고 `writeLineSeparator`에서 줄을 확정한다. `setSortByPosition(true)` 유지.
- [ ] **Step 5: 테스트 통과 확인** — mvn `-Dtest=PdfLinesTests,OcrDocumentTests,PdfPerformanceTests` → PASS.
- [ ] **Step 6: 커밋** — `PDF 줄바꿈 복원과 표·목차 줄 구분을 웹과 같은 규칙으로 적용`

### Task 6: 스캔 쪽만 전체 OCR, 본문 쪽은 이미지 영역만 OCR

**Files:**
- Create: `src/main/java/ai/actiondoc/ImageRegions.java`
- Modify: `src/main/java/ai/actiondoc/PdfPagePipeline.java:44-62` (`needsOcr`, `hasLargeImage` 삭제)
- Test: `src/test/java/ai/actiondoc/OcrDocumentTests.java`

**Interfaces:**
- Produces: `record ImageRegions.Region(double x, double y, double width, double height, long pixels)` (쪽 좌표, 회전 적용 전), `static List<Region> ImageRegions.find(PDPage page)`; `static BufferedImage ImageRegions.crop(BufferedImage rendered, PDPage page, Region region, float scale)`

- [ ] **Step 1: 실패하는 테스트 4개** (기존 `OcrService` 익명 서브클래스 방식으로 `recognize` 호출을 기록)
  - `textPageWithLargeImageOcrsOnlyTheImage`: 본문 40자 이상 + 큰 이미지 1개 → `recognize` 1회, 받은 이미지 크기가 쪽 전체보다 작고 이미지 영역 비율과 일치.
  - `scannedPageOcrsTheWholePage`: 이미지뿐인 쪽 → 쪽 전체 크기로 1회.
  - `textOnlyPageSkipsOcr`: 이미지 없는 본문 쪽 → 0회.
  - `rotatedPageCropsTheImageRegion` (Review Focus 2): `/Rotate 90` 쪽 → 잘라낸 이미지의 가로·세로가 회전을 반영.
  Run → FAIL.
- [ ] **Step 2: 구현** — `ImageRegions.find`는 `PDFStreamEngine`을 상속해 `Do` 연산자에서 `PDImageXObject`를 그릴 때 현재 CTM으로 단위 정사각형을 변환한 경계 상자를 모은다(폼 XObject 안쪽 포함). 판정은 웹 `read.js`와 같게: 본문 공백 제외 40자 미만이면서 이미지가 있거나 본문이 비었으면 쪽 전체 OCR, 아니면 `pixels >= 250_000`인 영역만 잘라 OCR(각 기준값의 이유를 주석으로). 영역별 OCR 결과는 줄 단위로 이어 하나의 `OcrService.Result`로 합치고 신뢰도는 최솟값, 낮은 신뢰도 줄 수는 합. `crop`은 렌더링 배율과 cropBox 원점, 쪽 회전을 반영.
- [ ] **Step 3: 테스트 통과 확인** — mvn `-Dtest=OcrDocumentTests` → PASS.
- [ ] **Step 4: 커밋** — `본문이 있는 PDF 쪽은 큰 이미지 영역만 OCR하고 스캔 쪽만 전체 OCR`

### Task 7: 문서 읽기 취소

**Files:**
- Modify: `src/main/java/ai/actiondoc/ApiController.java` (취소 엔드포인트, `document`에서 취소 신호 전달)
- Modify: `src/main/java/ai/actiondoc/DocumentService.java:17` (`normalize(MultipartFile file, String text, Consumer<DocumentProgress> progress, BooleanSupplier cancelled)` 추가, 기존 3인자 시그니처는 `() -> false`로 위임)
- Modify: `src/main/java/ai/actiondoc/PdfPagePipeline.java` (`read(PDDocument pdf, OcrService ocr, Consumer<Page> consume, Consumer<DocumentProgress> progress, BooleanSupplier cancelled)`)
- Modify: `desktop/Sources/Service.swift`, `desktop/Sources/AppModel.swift`, `desktop/Sources/Views.swift`
- Test: `src/test/java/ai/actiondoc/OcrDocumentTests.java`, `src/test/java/ai/actiondoc/ApiIntegrationTests.java`

**Interfaces:**
- Produces: `DELETE /api/documents/progress/{id}` (CSRF 필요, 200 `{"status":"cancelled"}`); 취소 시 예외 메시지 `"문서 읽기를 취소했습니다."`; Swift `APIClient.cancelDocument() async` (진행 중 `progressId` 사용), `AppModel.cancelReading()`

- [ ] **Step 1: 실패하는 테스트**
  - `cancelDuringOcrStopsWorkersAndNextReadWorks` (Review Focus 4): `recognize`가 인터럽트될 때까지 대기하는 OCR 스텁, 2쪽 스캔 PDF를 읽는 중 취소 → 2초 안에 위 메시지로 실패, 스텁이 인터럽트를 받음. 이어서 같은 `DocumentService`로 다른 PDF를 읽으면 성공.
  - `ApiIntegrationTests.cancelEndpointMarksProgress`: DELETE 후 같은 ID의 업로드가 취소 메시지로 실패.
  Run → FAIL.
- [ ] **Step 2: Java 구현** — 세션에 취소된 ID 집합을 두고 `document()`가 `() -> cancelled.contains(progressId)`를 넘긴다. `PdfPagePipeline.read`는 쪽마다 확인하고, `await`는 1초 단위로 기다리며 확인한다. 취소되면 대기 중 작업을 `cancel(true)`하고 예외를 던진다(기존 `finally`가 OCR 프로세스를 정리).
- [ ] **Step 3: 테스트 통과 확인** — mvn `-Dtest=OcrDocumentTests,ApiIntegrationTests` → PASS.
- [ ] **Step 4: Swift 구현** — `APIClient.document`가 쓰는 `progressId`를 `private(set) var currentProgressId`로 보관, `cancelDocument()`는 DELETE 요청. `AppModel.cancelReading()`은 API 취소 후 분석 `Task`를 취소하고 `status`를 "문서 읽기를 취소했습니다"로. `Views.swift`에서 문서를 읽는 동안(`busy`이고 문서가 아직 없을 때) "읽기 취소" 버튼 표시.
- [ ] **Step 5: 단계 A 빌드와 커밋** — 빌드 명령 → `DESKTOP_SELF_TEST_PASSED`. 커밋 `Mac 앱에 문서 읽기 취소 추가`

## 단계 B: 기본 정리

### Task 8: 기본 정리 규칙을 웹과 공유하고 Java로 옮기기

**Files:**
- Create: `shared/fixtures/basic-summary.json`
- Modify: `web/tests/summary.test.mjs` (규칙 사례를 fixture로)
- Create: `src/main/java/ai/actiondoc/BasicSummary.java`
- Create: `src/test/java/ai/actiondoc/BasicSummaryTests.java`

**Interfaces:**
- Produces: `record BasicSummary.Section(String id, String title, List<Block> evidence, List<Block> excerpts)`, `static List<Section> BasicSummary.summarize(List<Block> blocks, String purpose)` (잘못된 목적이면 `IllegalArgumentException("요약 목적을 다시 선택해 주세요.")`), `static final Map<String,String> BasicSummary.PURPOSE_TITLES` (`GENERAL`→`전체 정리`, `BUSINESS`→`공문·업무 요청`, `SUPPORT`→`지원사업·모집 공고`, `CONTRACT`→`요금·계약 안내`)

- [ ] **Step 1: fixture 작성** — `{"cases":[{"name","purpose","page":0,"text":"…","expect":{"<sectionId>":["발췌 원문", …]},"evidenceExcludes":{"<sectionId>":"정규식"}}]}`. `web/tests/summary.test.mjs`의 규칙 사례(목적별 묶음, 긴 조항, 없는 정보, 지원사업 제목 묶음, 업무 미리보기, 제목·날짜·표 행)를 옮기고, `page: 0`(HWP·TXT) 사례를 하나 추가(Review Focus 1). `blocksFromText(text, page)` 결과에 `summarize`를 적용한 발췌 텍스트를 기대값으로 둔다.
- [ ] **Step 2: 웹 테스트 전환** — fixture를 읽어 같은 단언. Run: `cd web && npm test` → PASS.
- [ ] **Step 3: 실패하는 Java 테스트** — `BasicSummaryTests.sharedFixtureMatchesWeb`: 각 사례의 `text`를 웹 `blocksFromText(text, page)`와 같게 나눠(줄 단위, 공백 정리, 빈 줄 제외, ID `b1`부터, 쪽 번호는 사례의 `page`) 블록을 만들고 `summarize` 결과의 발췌 텍스트·순서가 기대값과 같음, `evidenceExcludes` 정규식이 근거에 없음. Run → FAIL.
- [ ] **Step 4: 구현** — `web/src/summary.js`의 `summarize`와 보조 함수(`RULES`, `EXCEPTIONS`, `supportLabels`, `supportGroups`, `contentBlocks`, `heading`, `tableRow`, `dateValue`, `labels`, `factScore`, `factCandidates`, 발췌 선택·정렬)를 그대로 옮긴다. 정규식은 Java 문법으로 옮기되 의미를 바꾸지 않는다(유니코드 범위와 lookbehind 확인). 라벨과 다음 줄을 합친 블록의 ID는 웹과 같이 `b1–b2`.
- [ ] **Step 5: 테스트 통과 확인** — mvn `-Dtest=BasicSummaryTests` → PASS.
- [ ] **Step 6: 커밋** — `기본 정리 규칙을 Java로 옮기고 웹과 같은 사례로 검증`

### Task 9: 로컬 분석을 기본 정리로 교체

**Files:**
- Modify: `src/main/java/ai/actiondoc/AnalysisService.java:24-70` (로컬 분기 교체, 키워드 findings 루프 삭제)
- Create: `src/main/java/ai/actiondoc/DateWindow.java` (`NoticeReport.DateWindow` 이동)
- Delete: `src/main/java/ai/actiondoc/NoticeReport.java`, `src/main/java/ai/actiondoc/SummaryPurpose.java`
- Modify: `src/main/java/ai/actiondoc/ActionRoutes.java:16`, `src/main/java/ai/actiondoc/LlmDocumentAnalyzer.java:106` (`DateWindow` 참조)
- Replace: `src/test/java/ai/actiondoc/SummaryPurposeTests.java` → `LocalSummaryTests.java`
- Modify: `src/test/java/ai/actiondoc/SeoulNoticeTests.java`, `GovernmentNoticeRegressionTests.java`, `ActionDocTests.java` (로컬 결과를 검사하는 부분)

**Interfaces:**
- Consumes: `BasicSummary.summarize`, `BasicSummary.PURPOSE_TITLES` (Task 8)
- Produces: 로컬 `Analysis` — `sections`는 `BasicSummary` 섹션마다 `ReportSection("purpose-"+id, title, body, evidence, false)`. `body`는 발췌를 줄바꿈으로 이은 것, 발췌가 없으면 웹 `app.js`와 같은 문구(근거가 없을 때 "이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.", 근거는 있는데 발췌가 없을 때 "조건·예외를 중간에 자르지 않도록 긴 항목은 아래 원문 근거에서 확인합니다."). `findings`는 `action` 섹션 발췌마다 `Finding(category="action", date=AnalysisService.deadline(text))` — 작업 등록(`desktop` `saveTask`)이 계속 동작하도록. `overview`는 웹 안내문 "원문 문장을 찾아 항목별로 발췌한 무료 기본 정리입니다. 문맥을 새로 쓰는 AI 요약이나 개인별 자격 판단은 아닙니다.", `periodStatus`="UNKNOWN", `title`=문서 이름.

- [ ] **Step 1: 실패하는 테스트** — `LocalSummaryTests.localAnalysisEqualsBasicSummary`: 네 목적 각각 `analyze(doc,"","local",false,purpose)`의 섹션 ID·제목·근거 블록이 `BasicSummary.summarize(doc.blocks(), purpose)`와 같음. `actionExcerptsStayRegistrable`: "…제출해 주세요." 줄이 `action` finding이 되고 `TaskStore.create`로 등록됨. Run → FAIL.
- [ ] **Step 2: 구현** — `analyze`에서 로컬이면 위 형식으로 만들고 `withRoutes(ActionRoutes.extract(...))`는 유지. `NoticeReport.DateWindow`를 `DateWindow`로 옮기고 참조를 고친 뒤 `NoticeReport`·`SummaryPurpose` 삭제. `NoticeReport` 출력을 검사하던 테스트는 같은 입력의 `BasicSummary` 기대값을 검사하도록 바꾸고, 실제 문서 문자열은 합성 문자열로 바꾼다.
- [ ] **Step 3: 테스트 통과 확인** — mvn `test` 전체 → PASS.
- [ ] **Step 4: 커밋** — `Mac 로컬 분석을 웹과 같은 기본 정리로 교체`

### Task 10: 분석 결과를 마크다운 저장에 포함

**Files:**
- Modify: `desktop/Sources/Models.swift` (`AnalysisReport`에 `markdown(document:)`)
- Modify: `desktop/Sources/AppModel.swift` (`saveMarkdown`)
- Test: `desktop/Sources/SelfTest.swift`

**Interfaces:**
- Produces: `extension AnalysisReport { func markdown(document: SourceDocument) -> String }`

- [ ] **Step 1: 실패하는 자체 테스트** — 섹션 2개(근거 포함), 내 상황 비교 1개, action finding 1개인 보고서 → 결과에 `# <title>`, `## <섹션 제목>`, `> 원문 근거 (<SourceLocation.page> · <blockId>): <quote>`, `## 내 상황과 비교`, `## 할 일`, `## 원문` 아래 `document.markdown`이 있음. 이전 형식 기록(Review Focus 5): `actionRoutes`·`facts`·`personalChecks`가 없는 JSON을 `AnalysisReport`로 디코딩해 `markdown`이 비어 있지 않음. Run: 빌드 명령 → `DESKTOP_SELF_TEST_FAILED`.
- [ ] **Step 2: 구현** — 순서: 제목, 개요, 경고(`주의:`), 내 상황 비교(조건·상태·이유), 섹션(제목, 본문, 근거 줄), 할 일(action findings), 원문. `saveMarkdown()`은 보고서가 있으면 이 결과를, 없으면 기존처럼 원문만 저장. 파일 이름 기본값 `ActionDoc-정리.md`.
- [ ] **Step 3: 단계 B 빌드와 커밋** — 빌드 명령 → `DESKTOP_SELF_TEST_PASSED`. 커밋 `Mac 마크다운 저장에 분석 결과와 근거를 포함`

## 단계 D: Java 하드코딩 점검

### Task 11: Java 규칙 점검과 일반 규칙으로 정리

**Files:**
- Modify: 점검 결과에 따라 `src/main/java/ai/actiondoc/*.java`, `src/test/java/ai/actiondoc/*.java`
- 이미 확인된 대상: `LlmDocumentAnalyzer`의 `SYNTHESIZE`·`AUDIT` 프롬프트에 있는 특정 문서 예시("서울 거주라고 해도 전입예정자 예외…", "'20대'를 특정 나이로…", "대출한도를 지급액처럼…"), `AiProviderTests`·`SeoulNoticeTests`의 실제 문서 문자열, `ActionRoutes`의 정규식, `AnalysisService`에 남은 정규식(`isMissedActionImpact`, `extractDate`, `deadline`)

- [ ] **Step 1: 점검** — 웹 점검과 같은 기준(특정 사례만 피하려고 나열한 정규식·예외, 근거 없는 기준값, 실제 문서 문자열을 복사한 테스트. 제외: 기능 정의 키워드 사전, 문서화된 제한값, 보안 검증)으로 위 파일을 읽고, 항목별로 `파일:줄`, 피하려던 사례, 대체할 일반 규칙을 목록으로 남긴다(이 계획 파일 끝 "점검 결과" 절에 추가).
- [ ] **Step 2: 항목마다 테스트 먼저** — 대체 규칙이 같은 유형의 **다른** 합성 문자열에서도 동작하는 테스트를 쓰고 실패를 확인한다. 프롬프트 예시는 특정 지역·나이 대신 조건 유형으로 바꾼다(예: "거주지 조건에 전입예정자 같은 예외가 있으면, 예외 해당 여부가 입력되지 않은 경우 UNKNOWN").
- [ ] **Step 3: 구현과 통과 확인** — mvn `test` 전체 → PASS.
- [ ] **Step 4: 최종 빌드와 커밋** — 빌드 명령 → `DESKTOP_SELF_TEST_PASSED`. 커밋 `Java 규칙의 특정 문서용 처리를 일반 규칙으로 정리`

### Task 12: 앱 실행 확인

- [ ] **Step 1:** `ActionDoc AI.app` 실행 후 확인: (1) 스캔 PDF와 본문+이미지 PDF의 OCR 경고 쪽 번호, (2) 긴 PDF 읽는 중 "읽기 취소", (3) 같은 공개 공고 PDF를 웹과 Mac에서 같은 목적으로 기본 정리해 발췌가 같은지, (4) 기본 정리의 action 항목을 작업으로 등록, (5) 마크다운 저장 파일 내용, (6) 이전 기록 열기. AI 분석은 OpenAI 키가 있을 때만 확인한다.
- [ ] **Step 2:** 확인 결과와 남은 차이를 이 계획 파일 끝에 기록하고 커밋 `Mac 앱 웹 기능 반영 확인 결과 기록`

## 점검 결과

Task 11 점검(기준: 특정 사례만 피하려고 나열한 규칙·예외, 근거 없는 기준값, 실제 문서 문자열을 복사한 테스트). 줄 번호는 점검 시점(5451b79) 기준.

**일반 규칙으로 바꾼 항목**

| # | 파일:줄 | 피하려던 사례 | 대체한 일반 규칙 |
|---|---|---|---|
| 1 | `LlmDocumentAnalyzer.java:134` (SYNTHESIZE) | 테스트 프로필 하나("서울 거주 20대 직장인, 연봉") — 서울/전입예정자, '20대', 직장인, 연봉·부부합산, 주택소유·혼인·세대주·자녀, 성별 | 조건에 예외가 있으면 예외 해당 여부 미입력 시 UNKNOWN / 범위로 입력된 값을 특정 값으로 만들지 않고 경계에 걸치면 UNKNOWN / 입력된 직업·신분에서 세부 사항 추측 금지 / 산정 방식(합산 범위·세전/세후·기준 시점)이 다르거나 불명확하면 충족 단정 금지 / 조건에 해당하는 정보 미입력 시 UNKNOWN / 문서 조건에 없는 사용자 속성(예: 성별)은 판단에 사용 금지 |
| 2 | `LlmDocumentAnalyzer.java:133` (status 정의) | "정확한 나이·가구·세전" — 같은 프로필 | UNKNOWN=비교에 필요한 사용자 정보가 없거나 기준일·산정 방식·예외 해당 여부 확인 필요 |
| 3 | `LlmDocumentAnalyzer.java:131` (SYNTHESIZE) | 한 설명회 자료("여러 사업·기술·발표", "기술적 중단·비상정지·미공개 방침") | 한 문서에 여러 사업·주제가 묶이면 조건을 합치지 않음 / 문서가 미신청·미처리의 결과로 명시하지 않은 내용을 불이익으로 바꾸지 않음 |
| 4 | `LlmDocumentAnalyzer.java:122`, `:143` (EXTRACT·AUDIT) | 대출 공고의 "대출한도를 지급액처럼" | 한도·상한으로 적힌 금액을 실제 지급액·확정 금액으로 쓰지 않음(감사: 그런 항목 거부) |
| 5 | `LlmDocumentAnalyzer.java:119`, `:123` (EXTRACT) | 주택 공고("소득·거주·나이·주택 조건"), "공고번호와 제목의 연도를 신청일로" | 자격 조건(소득·거주·나이·주택·가구 등)과 그 기준(기준일·산정 방식) / 문서 번호나 제목에 들어 있는 연도·번호를 신청일이나 일정으로 만들지 않음 |
| 6 | `LlmDocumentAnalyzer.java:129`, `:142` (SYNTHESIZE·AUDIT) | "대출 가능 여부", "나이·소득·가구·무주택·혼인·세대주·전입계획", "전입 등 예외", "성별" | 개인의 전체 자격·기관 심사 결과 확정 금지 / 입력되지 않은 사용자 정보 추측 금지 / 불일치로 보여도 그 조건의 예외 해당 여부가 미확인이면 MISMATCH 거부 / 문서 조건에 없는 사용자 속성(예: 성별) 근거 거부 |
| 7 | `LlmDocumentAnalyzer.java:89`, `:91` (사용자 안내 문구) | 주택 공고의 확인 항목("거주지·정확한 나이·소득 산정 기준·주택 및 가구"), "대출 심사" | "문서의 각 조건과 그 기준일·산정 방식·예외에 내 상황이 해당하는지", "기관의 최종 자격 판단이나 심사 결과가 아닙니다" |
| 8 | `DateWindow.java:12` | 한 문서가 쓴 `∼`(U+223C)만 `~`에 덧붙여 허용 — `～`·`〜`로 쓴 기간은 기간으로 읽히지 않음 | 물결 모양 범위 표시 전체 `[~∼～〜]`(`DateWindow.RANGE`) |
| 9 | `ActionRoutes.java:13`, `:44-45` | 전화번호 범위에 `~`·`～`만 허용(`∼` 누락, 기간과 불일치) | 같은 `DateWindow.RANGE` 사용 |
| 10 | `AnalysisService.java:49`, `:60-68` | 예전 로컬 분할 분석의 잔재 `chunk()`의 근거 없는 7,500자 기준으로 `chunks` 값을 계산(기본 정리는 문서 전체를 한 번에 읽음) | `chunk()` 삭제, 로컬 보고서 `chunks=1` |
| 11 | `SeoulNoticeTests.java`, `GovernmentNoticeRegressionTests.java` | `~/Downloads`의 실제 파일명, 한 문서의 사실(13쪽) | 두 클래스 삭제. 실제 파일 확인은 기존 `PdfPerformanceTests`(경로는 `-Dactiondoc.pdfBenchmark`, 없으면 건너뜀)에 목적별 기본 정리 일치 검사를 더함. `unrelatedDatesCannotProduceApplicationPeriod`·약한 `announcementLinesAreNotTasks`는 `ActionDocTests`의 날짜 테스트로 합침(문서 번호는 날짜가 아님, 모든 물결 표시, 일만 적힌 끝 날짜, 끝 없는 기간은 null). 공고 머리·맺음 줄(제목, "다음과 같이 공고합니다", "많은 신청 바랍니다", 날짜·발행 기관)이 작업이 되지 않는지 합성 문서로 종단 확인 |
| 12 | `AiProviderTests.java:17-21` 등 | 실제 공고(나이 19~39세 청년세대주, 인천 전입예정자, 연소득 6천만원·부부합산 8천만원, 신청기간 형식)와 프롬프트 예시와 같은 프로필 | 같은 종류의 합성 값(만 25~44세 무주택 가구주, 가나시 전입예정자, 4천만원·가구합산 7천만원, 다나시 30대 자영업자 연소득 3500만원, 일만 적힌 끝 날짜의 기간 "2099. 6. 2. 09:00 ~ 26. 17:00") |
| 13 | `ActionRoutesTests.java:18-42` | 실제 공고의 사이트명·주소·메뉴 경로·콜센터·전화번호(youth.seoul.go.kr, 다산콜센터 02-120, 02-6358-0642~6, youth.incheon.go.kr) | 합성 값(youth.example.go.kr, 가나시 민원콜센터 031-120, 051-987-6540∼3, apply.example.or.kr) |

**유지한 항목(사유)**

- `ActionRoutes.java:13` 전화 정규식의 지역번호·`120`·15xx/16xx/18xx: 한국 전화번호 체계 사실(지역번호+120은 지자체 민원콜센터). 이유 주석 추가.
- `ActionRoutes.java:32`, `:42` 신청자·신청인·성명·생년월일·작성예 블록 제외, `:22`·`:33`·`:43` 신청·자료·문의 문맥 단어, `:16`·`LlmDocumentAnalyzer.java:105` 신청·접수·모집기간: 범주를 정의하는 키워드 사전(개인정보 양식 칸, 연락처 문맥, 기간 표제).
- `ActionRoutes.java:70-71` 경로 24개·근거 8개 상한: 링크가 많은 문서의 화면 목록 상한. 이유 주석 추가.
- `ActionRoutes.java:58-67` URL 검증, `HwpTextExtractor` 서명·암호·DRM·128MB 압축 해제·문단 길이 검사: 보안·형식 검증.
- `AnalysisService.java:16`, `:53-59` `extractDate`(완전한 날짜 하나만), `deadline`(까지·마감·기한·납부일 표지), `:18` `NO_ACTION`(부정형 문법): 일반 규칙과 기한 표지 사전.
- `PdfPagePipeline` 16px·40자·250,000픽셀: 이유 주석 있음. `isBlank`의 245·10만 분의 1 기준에 이유 주석 추가. 8분 제한은 오류 문구에 명시된 제한.
- `ImageRegions`: 기하 변환만 있고 기준값 없음.
- `DocumentService` 블록 1,800자 분할·6,000블록·OCR 신뢰도 0.8 경고, `OcrService` 0.35, `LlmDocumentAnalyzer` 32,000자 구간·응답 길이 상한(900/700/180/120/100): 특정 문서와 무관한 일반 처리 한도. 근거가 코드에 적혀 있지 않음(보고서 우려 사항에 기록).
- `BasicSummary`·`NumberCheck`·`PdfLines`: 웹 구현의 검증된 이식이라 점검 대상에서 제외.

## 확인 결과 (Task 12)

자동 확인 (2026-11-21, 브랜치 `mac-web-parity`):
- 웹 테스트: 22개 중 21개 통과, 1개 생략(실물 HWP 파일 테스트), 실패 0개
- Java 테스트: 89개 중 87개 통과, 2개 생략(실물 파일 경로를 지정할 때만 실행), 실패 0개
- `./desktop/build.command`: `DESKTOP_SELF_TEST_PASSED`

직접 확인이 필요한 항목 (앱 화면 조작이 필요해 자동화하지 못함):
1. 스캔 PDF와 본문+이미지 PDF의 OCR 경고 쪽 번호
2. 긴 PDF를 읽는 중 "읽기 취소"
3. 같은 공개 공고 PDF를 웹과 Mac에서 같은 목적으로 기본 정리했을 때 발췌가 같은지
4. 기본 정리의 할 일 항목을 작업으로 등록
5. 마크다운 저장 파일 내용
6. 이전 기록 열기
7. (OpenAI 키가 있을 때) AI 분석
