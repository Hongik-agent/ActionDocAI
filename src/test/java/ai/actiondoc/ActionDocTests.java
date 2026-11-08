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
        assertTrue(a.findings().stream().anyMatch(f->f.category().equals("fee")));
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
    @Test void missingInformationDoesNotInventImpact() throws Exception {
        Analysis a=analyzer.analyze(documents.normalize(null,"월 이용요금은 33,000원입니다."),"","local",false);
        assertTrue(a.findings().stream().noneMatch(f->f.category().equals("impact")));
        assertTrue(a.questions().stream().anyMatch(q->q.contains("영향이 없다는 의미는 아닙니다")));
        assertTrue(a.findings().stream().noneMatch(f->f.category().equals("action")));
    }
    @Test void automaticChangesDoNotCreateAction() throws Exception {
        Analysis a=analyzer.analyze(documents.normalize(null,"할인 신청이 필요 없습니다. 자동 적용됩니다."),"","local",false);
        assertTrue(a.findings().stream().noneMatch(f->f.category().equals("action")));
    }
    @Test void allLongDocumentBlocksAreCovered() {
        List<Block> blocks=new ArrayList<>();for(int i=0;i<60;i++)blocks.add(new Block("b"+i,1,"가".repeat(1800)));
        List<Block> covered=AnalysisService.chunk(blocks).stream().flatMap(List::stream).toList();assertEquals(blocks,covered);
    }
    @Test void technicalNegationsAreNotMissedActionImpacts() throws Exception {
        var doc=documents.normalize(null,"실제 갱신·운영을 의무화하지 않음\n비상정지와 수동전환 절차를 마련해야 함\n데이터는 공개하지 않고 비식별 데이터를 제공함\n안전관리형 과제로 지정하지 않음\n미제출 시 할인이 적용되지 않습니다.");
        var report=analyzer.analyze(doc,"","local",false);
        var impacts=report.findings().stream().filter(f->f.category().equals("impact")).toList();
        assertEquals(1,impacts.size());
        assertEquals("미제출 시 할인이 적용되지 않습니다.",impacts.get(0).quote());
        assertTrue(AnalysisService.isMissedActionImpact("요금 미납 시 서비스가 정지됩니다."));
        assertFalse(AnalysisService.isMissedActionImpact("요금 미납 여부를 확인합니다."));
    }
    @Test void longLocalReportKeepsAllEvidenceWithoutDumpingItIntoBody() throws Exception {
        StringBuilder source=new StringBuilder();
        for(int i=0;i<90;i++)source.append("기술 ").append(i).append("의 기존 설계를 변경합니다.\n");
        source.append("변경 조건: ").append("장기 운영 조건과 예외를 확인합니다. ".repeat(30));
        var doc=documents.normalize(null,source.toString());
        var report=analyzer.analyze(doc,"","local",false);
        var section=report.sections().stream().filter(x->x.id().equals("change")).findFirst().orElseThrow();
        assertTrue(section.body().length()<750);
        assertTrue(section.body().contains("원문 근거"));
        assertEquals(91,section.evidence().size());
        for(var e:section.evidence())assertTrue(doc.blocks().stream().anyMatch(b->b.id().equals(e.blockId())&&b.text().contains(e.quote())));
        assertFalse(section.body().contains("장기 운영"));
    }
    @Test void datesAreConservative() {
        assertEquals("2026-11-25",AnalysisService.extractDate("2026년 11월 25일까지"));
        assertNull(AnalysisService.extractDate("11월 25일까지"));assertNull(AnalysisService.extractDate("2026년 2월 30일까지"));
        assertNull(AnalysisService.extractDate("2026-11-25 또는 2026-12-01"));
        assertNull(AnalysisService.deadline("2026년 12월 1일부터 적용됩니다."));
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
