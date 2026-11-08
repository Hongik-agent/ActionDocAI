package ai.actiondoc;
import static ai.actiondoc.Models.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
@RestController
@RequestMapping("/api")
public class ApiController {
    private final DocumentService documents; private final AnalysisService analyzer; private final TaskStore tasks;
    public ApiController(DocumentService documents, AnalysisService analyzer, TaskStore tasks) { this.documents=documents; this.analyzer=analyzer; this.tasks=tasks; }
    @GetMapping("/config") public Map<String,Object> config(HttpSession session) {
        if (session.getAttribute("csrf") == null) session.setAttribute("csrf", UUID.randomUUID().toString());
        return Map.of("csrf", session.getAttribute("csrf"), "aiConfigured", analyzer.configured(), "maxFileMb", DocumentLimits.MAX_FILE_MB, "maxCharacters",160000,"maxPdfPages",DocumentLimits.MAX_PDF_PAGES);
    }
    @SuppressWarnings("unchecked") private <T> Map<String,T> cache(HttpSession s, String key) {
        synchronized(s) { if (s.getAttribute(key) == null) s.setAttribute(key, new LinkedHashMap<String,T>()); return (Map<String,T>)s.getAttribute(key); }
    }
    private <T> void remember(HttpSession session, String key, String id, T value) {
        synchronized(session) { Map<String,T> map=cache(session,key); if (!map.containsKey(id) && map.size()>=5) map.remove(map.keySet().iterator().next()); map.put(id,value); }
    }
    private <T> T get(HttpSession session,String key,String id) {
        synchronized(session) { T value=this.<T>cache(session,key).get(id); if(value==null) throw new NoSuchElementException("분석 세션이 만료되었거나 항목이 없습니다. 문서를 다시 입력해 주세요."); return value; }
    }
    @PostMapping(value="/documents", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document document(@RequestParam(required=false) MultipartFile file, @RequestParam(required=false) String text, @RequestParam(required=false) String progressId, HttpSession s) throws Exception {
        if(progressId!=null)validateProgressId(progressId);
        java.util.function.Consumer<DocumentProgress> progress=p->{if(progressId!=null)remember(s,"documentProgress",progressId,p);};
        progress.accept(new DocumentProgress("READING",0,0,"문서를 열고 있어요…"));
        try {
            Document doc=documents.normalize(file,text,progress); remember(s,"documents",doc.id(),doc);
            progress.accept(new DocumentProgress("COMPLETE",doc.pages(),doc.pages(),"문서 읽기 완료 · 분석을 준비하고 있어요…"));
            return doc;
        }catch(Exception e){progress.accept(new DocumentProgress("FAILED",0,0,"문서 읽기를 완료하지 못했습니다."));throw e;}
    }
    @GetMapping("/documents/progress/{id}") public DocumentProgress documentProgress(@PathVariable String id,HttpSession session) {
        validateProgressId(id);
        synchronized(session){return this.<DocumentProgress>cache(session,"documentProgress").getOrDefault(id,new DocumentProgress("WAITING",0,0,"파일을 전송하고 있어요…"));}
    }
    private static void validateProgressId(String id){if(!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("문서 처리 번호를 확인해 주세요.");}
    public record AnalyzeRequest(String documentId,String context,String mode,boolean consent,String purpose) {}
    @PostMapping("/analyses") public Analysis analyze(@RequestBody AnalyzeRequest request,HttpSession s) {
        AtomicBoolean busy;
        synchronized(s) { if(s.getAttribute("busy")==null) s.setAttribute("busy",new AtomicBoolean()); busy=(AtomicBoolean)s.getAttribute("busy"); }
        if(!busy.compareAndSet(false,true)) throw new IllegalArgumentException("이미 분석 중입니다. 현재 분석이 끝난 뒤 다시 시도해 주세요.");
        try { Document doc=get(s,"documents",request.documentId()); Analysis a=analyzer.analyze(doc,request.context(),request.mode(),request.consent(),request.purpose()); remember(s,"analyses",a.id(),a); return a; }
        finally { busy.set(false); }
    }
    public record TaskRequest(String analysisId,String findingId,String title,String description,String dueDate,boolean approved) {}
    @PostMapping("/tasks") public Task register(@RequestBody TaskRequest r,HttpSession s) throws Exception {
        Analysis a=get(s,"analyses",r.analysisId()); Document d=get(s,"documents",a.documentId());
        Finding f=a.findings().stream().filter(x->x.id().equals(r.findingId())).findFirst().orElseThrow(()->new IllegalArgumentException("유효한 추천 조치가 아닙니다."));
        return tasks.create(a,d,f,r.title(),r.description(),r.dueDate(),r.approved());
    }
    @GetMapping("/tasks") public List<Task> list() throws Exception { return tasks.list(); }
    public record StatusRequest(String status) {}
    @PatchMapping("/tasks/{id}") public Map<String,String> status(@PathVariable String id,@RequestBody StatusRequest r) throws Exception { tasks.update(id,r.status()); return Map.of("status",r.status()); }
}
