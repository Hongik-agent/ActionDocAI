package ai.actiondoc;
import static ai.actiondoc.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

class LocalSummaryTests {
    static final String MISSING="이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.",LONG="조건·예외를 중간에 자르지 않도록 긴 항목은 아래 원문 근거에서 확인합니다.";
    final DocumentService documents=new DocumentService();
    final AnalysisService analyzer=new AnalysisService(new ObjectMapper());
    @TempDir Path temporary;
    @Test void localAnalysisEqualsBasicSummary() throws Exception {
        var doc=documents.normalize(null,"지원 대상: 지역 중소기업\n지원 내용: 교육비 50,000원\n2027년 11월 25일까지 보고서를 제출해 주세요.\n담당 부서: 운영팀\n월 요금은 33,000원입니다.\n계약 해지 시 위약금이 있습니다.\n단, 신규 신청자는 제외됩니다.");
        for(String purpose:BasicSummary.PURPOSE_TITLES.keySet())assertMatchesBasicSummary(doc,analyzer.analyze(doc,"","local",false,purpose),purpose);
        // Older clients send no purpose.
        assertEquals(analyzer.analyze(doc,"","local",false,"GENERAL").sections(),analyzer.analyze(doc,"","local",false,null).sections());
        assertEquals("요약 목적을 다시 선택해 주세요.",assertThrows(IllegalArgumentException.class,()->analyzer.analyze(doc,"","local",false,"INVALID")).getMessage());
    }
    @Test void missingAndLongItemsUseWebNotes() throws Exception {
        var none=analyzer.analyze(documents.normalize(null,"오늘은 화창한 날씨입니다."),"","local",false,"CONTRACT");
        assertTrue(none.sections().stream().allMatch(s->s.evidence().isEmpty()&&s.body().equals(MISSING)));
        var doc=documents.normalize(null,"월 요금 안내: "+"조건과 범위를 함께 확인합니다. ".repeat(20));
        var cost=analyzer.analyze(doc,"","local",false,"CONTRACT").sections().get(0);
        assertEquals("purpose-cost",cost.id());assertEquals(1,cost.evidence().size());assertEquals(LONG,cost.body());
    }
    @Test void actionExcerptsStayRegistrable() throws Exception {
        var doc=documents.normalize(null,"사업 안내\n2027년 11월 25일까지 신청서를 제출해 주세요.\n결과는 개별 통보합니다.");
        var report=analyzer.analyze(doc,"","local",false,"GENERAL");
        var actions=report.findings().stream().filter(f->f.category().equals("action")).toList();
        assertEquals(1,actions.size());
        var f=actions.get(0);
        assertEquals("2027년 11월 25일까지 신청서를 제출해 주세요.",f.quote());assertEquals("2027-11-25",f.date());assertEquals(0,f.page());
        assertTrue(doc.blocks().stream().anyMatch(b->b.id().equals(f.blockId())&&b.text().equals(f.quote())));
        assertEquals("REVIEW_TASKS",report.decision());
        var task=new TaskStore(temporary.resolve("tasks.db").toString()).create(report,doc,f,"신청서 제출","설명",f.date(),true);
        assertEquals(f.quote(),task.quote());
    }
    @Test void textInputsUsePageZeroAndJsTrim() throws Exception {
        var pasted=documents.normalize(null,"\u00a0\ufeff 첫 줄입니다.\u00a0\n\t둘째\f  줄\ufeff");
        assertEquals(0,pasted.pages());
        assertEquals(List.of(new Block("b1",0,"첫 줄입니다."),new Block("b2",0,"둘째 줄")),pasted.blocks());
        assertTrue(pasted.markdown().startsWith("## 본문"));
        for(String name:List.of("a.txt","a.md","a.html")) {
            var doc=documents.normalize(new MockMultipartFile("file",name,"text/plain","<p>본문 한 줄</p>".getBytes(StandardCharsets.UTF_8)),null);
            assertEquals(0,doc.pages(),name);assertTrue(doc.blocks().stream().allMatch(b->b.page()==0),name);
        }
    }
    @Test void longWhitespaceRunsNormalizeInLinearTime() {
        // A regex trim restarts at every position of a whitespace run; 100,000 of them took minutes.
        var doc=assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->documents.normalize(null,"\u3000a"+"\u00a0".repeat(100_000)+"x\u3000"));
        assertTrue(doc.blocks().get(0).text().startsWith("a"));assertTrue(doc.blocks().get(doc.blocks().size()-1).text().endsWith("x"));
    }
    @Test void excerptsThatNeedNoActionAreNotTasks() throws Exception {
        var none=analyzer.analyze(documents.normalize(null,"별도 서류는 제출할 필요가 없습니다."),"","local",false,"GENERAL");
        var action=none.sections().stream().filter(s->s.id().equals("purpose-action")).findFirst().orElseThrow();
        assertEquals("별도 서류는 제출할 필요가 없습니다.",action.body());
        assertTrue(none.findings().isEmpty());
        var doc=documents.normalize(null,"신청서를 3월 20일까지 제출해 주세요.\n별도 서류는 제출할 필요가 없습니다.\n추가 자료는 보내지 않아도 됩니다.");
        var report=analyzer.analyze(doc,"","local",false,"GENERAL");
        assertTrue(report.sections().stream().filter(s->s.id().equals("purpose-action")).findFirst().orElseThrow().evidence().stream().anyMatch(e->e.quote().equals("별도 서류는 제출할 필요가 없습니다.")));
        assertEquals(List.of("신청서를 3월 20일까지 제출해 주세요."),report.findings().stream().map(Finding::quote).toList());
    }
    static void assertMatchesBasicSummary(Document doc,Analysis report,String purpose) {
        var expected=BasicSummary.summarize(doc.blocks(),purpose);
        assertEquals(expected.size(),report.sections().size(),purpose);
        var actions=new ArrayList<Finding>();
        for(int i=0;i<expected.size();i++) {
            var e=expected.get(i);var s=report.sections().get(i);
            assertEquals("purpose-"+e.id(),s.id(),purpose);assertEquals(e.title(),s.title(),purpose);assertFalse(s.aiRefined());
            assertEquals(e.evidence().stream().map(b->new Evidence(b.id(),b.page(),b.text())).toList(),s.evidence(),purpose);
            String body=e.excerpts().isEmpty()?(e.evidence().isEmpty()?MISSING:LONG):String.join("\n",e.excerpts().stream().map(Block::text).toList());
            assertEquals(body,s.body(),purpose);
            if(e.id().equals("action"))for(var b:e.excerpts())if(!AnalysisService.NO_ACTION.matcher(b.text()).find())actions.add(new Finding("action-"+b.id(),"action",e.title(),b.text(),b.text(),b.id(),b.page(),AnalysisService.deadline(b.text())));
        }
        assertEquals(actions,report.findings(),purpose);
        assertEquals(doc.name(),report.title());assertEquals("local",report.mode());assertEquals("UNKNOWN",report.periodStatus());
        assertEquals("원문 문장을 찾아 항목별로 발췌한 무료 기본 정리입니다. 문맥을 새로 쓰는 AI 요약이나 개인별 자격 판단은 아닙니다.",report.overview());
    }
}
