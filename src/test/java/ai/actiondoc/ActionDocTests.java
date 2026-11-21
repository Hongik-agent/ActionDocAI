package ai.actiondoc;
import static org.junit.jupiter.api.Assertions.*;
import static ai.actiondoc.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.io.*;
class ActionDocTests {
    DocumentService documents = new DocumentService();
    AnalysisService analyzer = new AnalysisService(new ObjectMapper());
    @TempDir Path temporary;
    @Test void normalizesTextAndKeepsEvidence() throws Exception {
        Document d=documents.normalize(null,"2026년 12월 1일부터 월 요금은 36,300원입니다.\n2026년 11월 25일까지 할인 신청서를 제출해 주세요.\n미제출 시 할인이 적용되지 않습니다.");
        assertTrue(d.markdown().contains("<!-- b2 -->"));
        Analysis a=analyzer.analyze(d,"","local",false);
        Finding f=a.findings().stream().filter(x->x.category().equals("action")).findFirst().orElseThrow();
        assertEquals("2026-11-25",f.date());
        assertTrue(d.blocks().stream().anyMatch(b->b.id().equals(f.blockId())&&b.text().contains(f.quote())));
        assertFalse(a.findings().stream().anyMatch(x->x.category().equals("action")&&x.quote().contains("12월")));
    }
    @Test void emptyAndInvalidFilesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->documents.normalize(null,"   "));
        assertThrows(IllegalArgumentException.class,()->documents.normalize(new MockMultipartFile("file","x.pdf","application/pdf","not a pdf".getBytes()),null));
        assertThrows(IllegalArgumentException.class,()->documents.normalize(new MockMultipartFile("file","x.exe","text/plain","hello".getBytes()),null));
    }
    @Test void htmlScriptsAreExcluded() throws Exception {
        var file=new MockMultipartFile("file","x.html","text/html","<html><body><h1>월 요금 33000원</h1><script>steal()</script><style>.hidden{}</style></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var d=documents.normalize(file,null);assertTrue(d.markdown().contains("33000원"));assertFalse(d.markdown().contains("steal"));
    }
    @Test void textPdfKeepsPageAndImagePdfIsRejected() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var pdf=new PDDocument()){
            for(int i=0;i<2;i++){var page=new PDPage();pdf.addPage(page);try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,700);content.showText("Page "+(i+1)+": fee 33000 KRW");content.endText();}}
            pdf.save(bytes);
        }
        var d=documents.normalize(new MockMultipartFile("file","sample.pdf","application/pdf",bytes.toByteArray()),null);
        assertEquals(2,d.pages());assertEquals(2,d.blocks().get(1).page());
        bytes.reset();try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(bytes);}
        final byte[] image=bytes.toByteArray();assertThrows(IllegalArgumentException.class,()->documents.normalize(new MockMultipartFile("file","scan.pdf","application/pdf",image),null));
    }
    @Test void fiftyPagesAllowedAndFiftyOneRejectedBeforeOcr() throws Exception {
        assertEquals(50,documents.normalize(pdf(50,true),null).pages());
        // Blank 51-page PDF would fail with 'no text' if it reached extraction.
        var failure=assertThrows(IllegalArgumentException.class,()->documents.normalize(pdf(51,false),null));
        assertTrue(failure.getMessage().contains("51쪽"));assertTrue(failure.getMessage().contains("50쪽까지"));
    }
    private MockMultipartFile pdf(int count,boolean text) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var pdf=new PDDocument()) {
            for(int i=0;i<count;i++) {
                var page=new PDPage();pdf.addPage(page);
                if(text)try(var stream=new PDPageContentStream(pdf,page)){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(40,700);stream.showText("Page "+(i+1)+": document summary source");stream.endText();}
            }
            pdf.save(bytes);
        }
        return new MockMultipartFile("file","notice.pdf","application/pdf",bytes.toByteArray());
    }
    @Test void missingInformationDoesNotInventAction() throws Exception {
        Analysis a=analyzer.analyze(documents.normalize(null,"월 이용요금은 33,000원입니다."),"","local",false);
        assertTrue(a.findings().isEmpty());assertEquals("NEEDS_REVIEW",a.decision());
        var action=a.sections().stream().filter(x->x.id().equals("purpose-action")).findFirst().orElseThrow();
        assertTrue(action.evidence().isEmpty());assertTrue(action.body().contains("찾지 못했습니다"));
    }
    @Test void automaticChangesDoNotCreateAction() throws Exception {
        Analysis a=analyzer.analyze(documents.normalize(null,"할인 신청이 필요 없습니다. 자동 적용됩니다."),"","local",false);
        assertTrue(a.findings().stream().noneMatch(f->f.category().equals("action")));
    }
    @Test void localSummaryReadsTheWholeDocumentInOnePass() throws Exception {
        var doc=documents.normalize(null,(("가".repeat(1700))+"\n").repeat(60));
        assertEquals(1,analyzer.analyze(doc,"","local",false).chunks());
    }
    @Test void longLocalReportKeepsAllEvidenceWithoutDumpingItIntoBody() throws Exception {
        StringBuilder source=new StringBuilder();
        for(int i=0;i<90;i++)source.append("기술 ").append(i).append("의 기존 설계를 변경합니다.\n");
        source.append("변경 조건: ").append("장기 운영 조건과 예외를 확인합니다. ".repeat(30));
        var doc=documents.normalize(null,source.toString());
        var report=analyzer.analyze(doc,"","local",false,"CONTRACT");
        var section=report.sections().stream().filter(x->x.id().equals("purpose-change")).findFirst().orElseThrow();
        assertTrue(section.body().length()<750);
        assertEquals(3,section.body().split("\n").length);
        assertEquals(91,section.evidence().size());
        for(var e:section.evidence())assertTrue(doc.blocks().stream().anyMatch(b->b.id().equals(e.blockId())&&b.text().contains(e.quote())));
        assertFalse(section.body().contains("장기 운영"));
    }
    @Test void datesAreConservative() {
        assertEquals("2026-11-25",AnalysisService.extractDate("2026년 11월 25일까지"));
        assertNull(AnalysisService.extractDate("11월 25일까지"));assertNull(AnalysisService.extractDate("2026년 2월 30일까지"));
        assertNull(AnalysisService.extractDate("2026-11-25 또는 2026-12-01"));
        assertNull(AnalysisService.deadline("2026년 12월 1일부터 적용됩니다."));
        // Document numbers are not dates.
        assertNull(AnalysisService.extractDate("가나시 공고 제2027-1234호"));
    }
    @Test void noticeHeaderAndClosingLinesAreNotTasks() throws Exception {
        var lines=List.of("다라군 공고 제2028-345호","2028년 다라군 소상공인 경영 지원사업 참여자 모집 공고","다라군 소상공인 경영 지원사업 참여자를 다음과 같이 공고합니다.","많은 신청 바랍니다.","2028. 2. 10.","다라군수");
        var doc=documents.normalize(null,String.join("\n",lines)+"\n2028년 3월 15일까지 신청서를 제출해 주세요.");
        var quotes=analyzer.analyze(doc,"","local",false).findings().stream().map(Finding::quote).toList();
        assertEquals(List.of("2028년 3월 15일까지 신청서를 제출해 주세요."),quotes);
    }
    @Test void applicationWindowNeedsAFullRangeAndAcceptsEveryTildeMark() {
        assertNull(DateWindow.parse("가나시 공고 제2027-1234호"));
        assertNull(DateWindow.parse("접수기간: 2027년 4월 1일부터"));
        for(String mark:List.of("~","∼","～","〜")) {
            var range=DateWindow.parse("접수기간: 2028. 4. 1.(토) 09:00 "+mark+" 4. 14.(금) 18:00");
            assertNotNull(range,mark);assertEquals(java.time.LocalDate.of(2028,4,1),range.start());assertEquals(java.time.LocalDate.of(2028,4,14),range.end());
        }
        // A day-only end keeps the start's year and month.
        var dayOnly=DateWindow.parse("접수기간: 2028년 5월 8일 〜 19일");
        assertEquals(java.time.LocalDate.of(2028,5,19),dayOnly.end());
        var full=DateWindow.parse("모집기간: 2027년 12월 28일 ～ 2028년 1월 3일");
        assertEquals(java.time.LocalDate.of(2028,1,3),full.end());
    }
    @Test void approvalIdempotencyStatusAndPersistence() throws Exception {
        var path=temporary.resolve("tasks.db");var store=new TaskStore(path.toString());
        var doc=documents.normalize(null,"2026년 11월 25일까지 신청서를 제출해 주세요.");var analysis=analyzer.analyze(doc,"","local",false);
        var f=analysis.findings().stream().filter(x->x.category().equals("action")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->store.create(analysis,doc,f,"할인 신청","설명",null,false));assertEquals(0,store.list().size());
        assertThrows(IllegalArgumentException.class,()->store.create(analysis,doc,f,"할인 신청","설명","2026-02-30",true));
        var task=store.create(analysis,doc,f,"할인 신청","설명","2026-11-25",true);
        assertEquals(task.id(),store.create(analysis,doc,f,"중복","설명",null,true).id());assertEquals(1,store.list().size());
        store.update(task.id(),"DONE");assertEquals("DONE",new TaskStore(path.toString()).list().get(0).status());
        assertThrows(NoSuchElementException.class,()->store.update("missing","DONE"));assertThrows(IllegalArgumentException.class,()->store.update(task.id(),"BROKEN"));
    }
    @Test void aiRequiresConfigurationAndConsent() throws Exception {
        var doc=documents.normalize(null,"월 요금 33000원");
        assertThrows(IllegalArgumentException.class,()->analyzer.analyze(doc,"","ai",false));
    }
}
