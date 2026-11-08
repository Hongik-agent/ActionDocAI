package ai.actiondoc;
import java.util.List;
public final class Models {
    // page/pages == 0 means physical page information is unavailable (HWP).
    public record Block(String id, int page, String text) {}
    public record DocumentProgress(String phase,int completedPages,int totalPages,String message) {}
    public record Document(String id, String name, String type, int pages, int characters, String markdown, List<Block> blocks, List<String> warnings, List<Integer> ocrPages) {
 public Document(String id,String name,String type,int pages,int characters,String markdown,List<Block> blocks,List<String> warnings){this(id,name,type,pages,characters,markdown,blocks,warnings,List.of());}
 }
    public record Finding(String id, String category, String title, String detail, String quote, String blockId, int page, String date) {}
    public record Evidence(String blockId,int page,String quote) {}
    public record ReportSection(String id,String title,String body,List<Evidence> evidence,boolean aiRefined) {}
    public record ExtractedFact(String id,String category,String topic,String statement,List<Evidence> evidence) {}
    public record PersonalCheck(String id,String criterion,String status,String reason,String profileQuote,List<Evidence> evidence) {}
    public record PipelineStage(String name,String status,String detail) {}
    public record ActionRoute(String id,String kind,String title,String url,String displayAddress,String hint,String navigation,String status,String applicationEndsOn,List<Evidence> evidence) {}
    public record Analysis(String id, String documentId, String title, String mode, String decision, List<Finding> findings, List<String> questions, List<String> warnings, int chunks, String createdAt,String documentType,String overview,List<ReportSection> sections,String periodStatus,List<PersonalCheck> personalChecks,List<ExtractedFact> facts,List<PipelineStage> stages,List<ActionRoute> actionRoutes) {
        public Analysis(String id,String documentId,String title,String mode,String decision,List<Finding> findings,List<String> questions,List<String> warnings,int chunks,String createdAt,String documentType,String overview,List<ReportSection> sections,String periodStatus,List<PersonalCheck> personalChecks,List<ExtractedFact> facts,List<PipelineStage> stages){this(id,documentId,title,mode,decision,findings,questions,warnings,chunks,createdAt,documentType,overview,sections,periodStatus,personalChecks,facts,stages,List.of());}
        public Analysis withRoutes(List<ActionRoute> routes){return new Analysis(id,documentId,title,mode,decision,findings,questions,warnings,chunks,createdAt,documentType,overview,sections,periodStatus,personalChecks,facts,stages,routes);}
        public Analysis(String id,String documentId,String title,String mode,String decision,List<Finding> findings,List<String> questions,List<String> warnings,int chunks,String createdAt,String documentType,String overview,List<ReportSection> sections,String periodStatus){this(id,documentId,title,mode,decision,findings,questions,warnings,chunks,createdAt,documentType,overview,sections,periodStatus,List.of(),List.of(),List.of());}
    }
    public record Task(String id, String analysisId, String findingId, String title, String description, String dueDate, String status, String sourceName, String quote, int page, String createdAt) {}
}
