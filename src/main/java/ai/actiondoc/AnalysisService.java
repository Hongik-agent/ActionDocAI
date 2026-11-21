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
        if(purpose==null)purpose="GENERAL"; // Older clients retain their existing behavior.
        if(!BasicSummary.PURPOSE_TITLES.containsKey(purpose))throw new IllegalArgumentException("요약 목적을 다시 선택해 주세요.");
        if (!Set.of("local", "ai").contains(mode)) throw new IllegalArgumentException("분석 모드를 확인해 주세요.");
        if (context == null) context = "";
        if (context.length() > 4000) throw new IllegalArgumentException("추가 정보는 4,000자 이하여야 합니다.");
        if (mode.equals("ai") && !configured()) throw new IllegalArgumentException("AI 분석은 서버에 OPENAI_API_KEY를 설정한 뒤 사용할 수 있습니다.");
        if (mode.equals("ai") && !consent) throw new IllegalArgumentException("외부 AI로 본문을 전송하려면 동의가 필요합니다.");
        Analysis report=mode.equals("ai")?new LlmDocumentAnalyzer(json,http,apiKey,model).analyze(doc,context,purpose):basicSummary(doc,context,purpose);
        return report.withRoutes(ActionRoutes.extract(doc,report));
    }
    /** The web's free summary (web/src/summary.js) as a report; action excerpts become findings so they can be registered as tasks. */
    private static Analysis basicSummary(Document doc,String context,String purpose){
        List<ReportSection> sections=new ArrayList<>();List<Finding> findings=new ArrayList<>();
        for(var s:BasicSummary.summarize(doc.blocks(),purpose)){
            // Same notes as render() in web/src/app.js.
            String body=!s.excerpts().isEmpty()?String.join("\n",s.excerpts().stream().map(Block::text).toList())
                :s.evidence().isEmpty()?"이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.":"조건·예외를 중간에 자르지 않도록 긴 항목은 아래 원문 근거에서 확인합니다.";
            sections.add(new ReportSection("purpose-"+s.id(),s.title(),body,s.evidence().stream().map(b->new Evidence(b.id(),b.page(),b.text())).toList(),false));
            if(s.id().equals("action"))for(var b:s.excerpts())findings.add(new Finding("action-"+b.id(),"action",s.title(),b.text(),b.text(),b.id(),b.page(),deadline(b.text())));
        }
        List<String> warnings=new ArrayList<>(doc.warnings());
        if(!context.isBlank())warnings.add("추가 정보는 개인별 자격 충족 판정에 사용하지 않았습니다. 해당 조건을 원문과 대조해 주세요.");
        return new Analysis(UUID.randomUUID().toString(),doc.id(),doc.name(),"local",findings.isEmpty()?"NEEDS_REVIEW":"REVIEW_TASKS",List.copyOf(findings),List.of(),List.copyOf(warnings),chunk(doc.blocks()).size(),Instant.now().toString(),"GENERAL",
            "원문 문장을 찾아 항목별로 발췌한 무료 기본 정리입니다. 문맥을 새로 쓰는 AI 요약이나 개인별 자격 판단은 아닙니다.",List.copyOf(sections),"UNKNOWN");
    }
    static String bounded(String text, int max) { if (text.length() > max) throw new IllegalArgumentException("분석 응답이 허용 길이를 초과했습니다. 다시 분석해 주세요."); return text; }
    static String extractDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        try { String date = LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))).toString(); return m.find() ? null : date; }
        catch (DateTimeException e) { return null; }
    }
    static String deadline(String text) { return text.matches(".*(까지|마감|기한|납부일).*?") ? extractDate(text) : null; }
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
