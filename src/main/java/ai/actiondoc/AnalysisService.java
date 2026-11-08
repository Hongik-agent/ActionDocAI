package ai.actiondoc;
import static ai.actiondoc.Models.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
@Service
public class AnalysisService {
    private final ObjectMapper json;
    private final HttpClient http;
    private final String apiKey, model;
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[.년/\\-]\\s*(\\d{1,2})[.월/\\-]\\s*(\\d{1,2})일?");
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisService(ObjectMapper json) { this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),System.getenv().getOrDefault("OPENAI_API_KEY", ""),System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4.1-mini")); }
    AnalysisService(ObjectMapper json, HttpClient http, String apiKey, String model) { this.json=json; this.http=http; this.apiKey=apiKey; this.model=model; }
    public boolean configured() { return !apiKey.isBlank(); }
    public Analysis analyze(Document doc,String context,String mode,boolean consent){
        return analyze(doc,context,mode,consent,"GENERAL");
    }
    public Analysis analyze(Document doc,String context,String mode,boolean consent,String purpose){
        String selected=SummaryPurpose.validate(purpose);
        Analysis report=analyzeContent(doc,context,mode,consent,selected);
        if(mode.equals("local"))report=SummaryPurpose.apply(doc,report,selected);
        return report.withRoutes(ActionRoutes.extract(doc,report));
    }
    private Analysis analyzeContent(Document doc, String context, String mode, boolean consent,String purpose) {
        if (!Set.of("local", "ai").contains(mode)) throw new IllegalArgumentException("분석 모드를 확인해 주세요.");
        if (context == null) context = "";
        if (context.length() > 4000) throw new IllegalArgumentException("추가 정보는 4,000자 이하여야 합니다.");
        if (mode.equals("ai") && !configured()) throw new IllegalArgumentException("AI 분석은 서버에 OPENAI_API_KEY를 설정한 뒤 사용할 수 있습니다.");
        if (mode.equals("ai") && !consent) throw new IllegalArgumentException("외부 AI로 본문을 전송하려면 동의가 필요합니다.");
        if(mode.equals("ai"))return new LlmDocumentAnalyzer(json,http,apiKey,model).analyze(doc,context,purpose);
        if(NoticeReport.isNotice(doc) && !mode.equals("ai")) {
            var draft=NoticeReport.build(doc);List<ReportSection> sections=draft.sections();List<String> reportWarnings=new ArrayList<>(doc.warnings());
            if(!context.isBlank())reportWarnings.add("추가 정보는 개인별 자격 충족 판정에 사용하지 않았습니다. 해당 조건을 원문과 대조해 주세요.");
            reportWarnings.add("AI를 사용하지 않은 원문 항목 정리입니다. 문맥 요약은 AI 연결 후 OpenAI 분석으로 실행해 주세요.");
            return new Analysis(UUID.randomUUID().toString(),doc.id(),draft.title(),mode,"NEEDS_REVIEW",List.of(),List.of("본인에게 적용되는 대상 조건과 예외는 원문에서 확인해 주세요. 원문 정리는 개인별 자격을 판단하지 않습니다."),reportWarnings,chunk(doc.blocks()).size(),Instant.now().toString(),draft.type(),draft.overview(),sections,draft.periodStatus());
        }
        List<List<Block>> chunks = chunk(doc.blocks());
        List<Finding> findings = new ArrayList<>(); Set<String> questions = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>(doc.warnings());
        if(chunks.size()>1) warnings.add("긴 문서를 구간별로 분석했습니다. 다른 구간에 있는 적용 조건·예외와의 관계는 원문을 함께 확인해 주세요.");
        if (!mode.equals("ai")) {
            warnings.add("규칙 기반 미리보기입니다. 문맥·적용 대상·변경 전후 관계를 이해하는 AI 분석이 아니며, 금액·날짜·행동 키워드가 있는 원문을 찾아 표시합니다.");
            if(!context.isBlank()) warnings.add("규칙 기반 미리보기는 추가 정보를 적용 여부 판단에 반영하지 않습니다. AI 분석을 선택해 주세요.");
            for (Block b : doc.blocks()) {
                String t = b.text();
                if (t.matches(".*(변경|인상|인하|기존|신설|개정|종전).*")) add(findings,b,"change","변경 조건 확인",t,null);
                if (t.matches(".*(\\d[\\d,]*\\s*원|요금|수수료|보험료|청구|부가세|할인|위약금).*")) add(findings,b,"fee","요금·금액 확인",t,null);
                if (extractDate(t)!=null && t.matches(".*(신청|접수|시행|적용|마감|발표|상담|기한|종료|부터|까지).*" ) && !t.matches(".*(공고\\s*제|서식|생년월일).*")) add(findings,b,"date","날짜와 적용 조건 확인",t,extractDate(t));
                if (t.matches(".*(신청|제출|납부|접수|회신|동의서|방문|등록).*" ) && t.matches(".*(해\\s*주세요|하십시오|해야|필요합니다|바랍니다|하세요|까지|기한|마감).*")) {
                    if (!t.matches(".*(필요\\s*없|불필요|하지\\s*않아도|조치\\s*없|자동\\s*적용|많은\\s*신청\\s*바랍니다).*"))
                        add(findings,b,"action","필요한 조치 검토",t,deadline(t));
                }
                if (isMissedActionImpact(t)) add(findings,b,"impact","미처리 영향 확인",t,null);
            }
            questions.add("본인에게 적용되는 조건인지 원문과 계약 내용을 확인해 주세요.");
        }
        if (findings.stream().noneMatch(f -> f.category().equals("change"))) questions.add("변경 전후 조건을 확인하지 못했습니다. 필요하면 기존 계약 내용을 추가해 주세요.");
        if (findings.stream().noneMatch(f -> f.category().equals("impact"))) questions.add("미처리 영향은 확인되지 않았습니다. 영향이 없다는 의미는 아닙니다.");
        boolean action = findings.stream().anyMatch(f -> f.category().equals("action"));
        String decision = action ? "REVIEW_TASKS" : "NEEDS_REVIEW";
        List<ReportSection> sections=sectionsFromFindings(findings);
        var excerpts=doc.blocks().stream().filter(b->b.text().length()>=30 && b.text().length()<=240).limit(3).toList();
        if(!excerpts.isEmpty())sections.add(0,new ReportSection("source-intro","문서 앞부분의 주요 문장",sourcePreview(excerpts.stream().map(Block::text).toList()),excerpts.stream().map(b->new Evidence(b.id(),b.page(),b.text())).toList(),false));
        return new Analysis(UUID.randomUUID().toString(), doc.id(), doc.name(), mode, decision, List.copyOf(findings), List.copyOf(questions), List.copyOf(new LinkedHashSet<>(warnings)), chunks.size(), Instant.now().toString(),"GENERAL","키워드가 있는 원문을 항목별로 발췌했습니다. 문서 전체의 요약이나 개인별 적용 판단은 아닙니다.",sections,"UNKNOWN");
    }
    static boolean isMissedActionImpact(String text) {
        // Negation and technical downtime alone are not consequences of a missed task.
        String t=text.replaceAll("\\s+", " ");
        return t.matches(".*(미납|미제출|미처리|미신청|미접수|미등록|연체|기한[^.]{0,30}(경과|초과)|(?:신청|제출|납부|등록)[^.]{0,20}하지 않을 경우).*" )
                && t.matches(".*(불이익|불가|제외|취소|상실|중단|정지|가산|연체료|위약금|적용되지|적용되지 않|지급되지|받을 수 없|자동.{0,4}갱신).*" );
    }
    static String sourcePreview(List<String> source) {
        var unique=new LinkedHashSet<>(source);
        List<String> shown=new ArrayList<>();int length=0;
        for(String text:unique) {
            // Keep complete source clauses: cutting a sentence can remove its exception.
            if(text.length()>240 || shown.size()>=3 || length+text.length()>600)continue;
            shown.add(text);length+=text.length();
        }
        String body=shown.stream().map(t->"• "+t).reduce((a,b)->a+"\n"+b).orElse("");
        if(shown.size()<unique.size())body+=(body.isEmpty()?"":"\n\n")+"전체 "+unique.size()+"개 원문 항목 중 짧은 발췌 "+shown.size()+"개를 표시합니다. 긴 항목과 나머지 조건·예외는 아래 원문 근거에서 확인해 주세요.";
        return body;
    }
    private List<ReportSection> sectionsFromFindings(List<Finding> findings){
        Map<String,String> labels=new LinkedHashMap<>();labels.put("change","무엇이 바뀌나요?");labels.put("fee","금액과 비용은 어떻게 되나요?");labels.put("date","언제 적용되고 언제까지인가요?");labels.put("action","어떤 준비와 조치가 필요한가요?");labels.put("impact","놓치면 어떤 영향이 있나요?");
        List<ReportSection> out=new ArrayList<>();
        for(var entry:labels.entrySet()){
            var items=findings.stream().filter(f->f.category().equals(entry.getKey())).toList();if(items.isEmpty())continue;
            String body=sourcePreview(items.stream().map(Finding::detail).toList());
            out.add(new ReportSection(entry.getKey(),entry.getValue(),body,items.stream().map(f->new Evidence(f.blockId(),f.page(),f.quote())).toList(),false));
        }return out;
    }
    static String bounded(String text, int max) { if (text.length() > max) throw new IllegalArgumentException("분석 응답이 허용 길이를 초과했습니다. 다시 분석해 주세요."); return text; }
    static String extractDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        try { String date = LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString(); return m.find() ? null : date; }
        catch (DateTimeException e) { return null; }
    }
    static String deadline(String text) { return text.matches(".*(까지|마감|기한|납부일).*?") ? extractDate(text) : null; }
    private void add(List<Finding> fs, Block b, String c, String t, String d, String date) { add(fs,b,c,t,d,date,b.text()); }
    private void add(List<Finding> fs, Block b, String c, String t, String d, String date, String quote) {
        if (fs.stream().noneMatch(f -> f.category().equals(c) && f.blockId().equals(b.id()) && f.quote().equals(quote)))
            fs.add(new Finding(UUID.randomUUID().toString(), c, t, d, quote, b.id(), b.page(), date));
    }
    static List<List<Block>> chunk(List<Block> blocks) {
        List<List<Block>> chunks = new ArrayList<>(); List<Block> current = new ArrayList<>(); int size = 0;
        for (Block b : blocks) {
            if (!current.isEmpty() && size + b.text().length() > 7500) { chunks.add(List.copyOf(current)); current.clear(); size = 0; }
            current.add(b); size += b.text().length();
        }
        if (!current.isEmpty()) chunks.add(List.copyOf(current));
        return chunks;
    }
}
