package ai.actiondoc;
import static ai.actiondoc.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import java.io.ByteArrayOutputStream;

class SummaryPurposeTests {
    final DocumentService documents=new DocumentService();
    final AnalysisService analyzer=new AnalysisService(new ObjectMapper());
    @Test void purposeChangesSectionsAndPreservesSourceEvidence() throws Exception {
        var doc=documents.normalize(null,"지원 대상: 지역 중소기업\n지원 내용: 교육비 50,000원\n2027년 11월 25일까지 보고서를 제출해 주세요.\n담당 부서: 운영팀\n월 요금은 33,000원입니다.\n계약 해지 시 위약금이 있습니다.\n단, 신규 신청자는 제외됩니다.");
        var business=analyzer.analyze(doc,"","local",false,"BUSINESS");
        var support=analyzer.analyze(doc,"","local",false,"SUPPORT");
        var contract=analyzer.analyze(doc,"","local",false,"CONTRACT");
        assertEquals("purpose-requests",business.sections().get(0).id());
        assertTrue(business.sections().get(0).body().contains("보고서"));
        assertEquals("purpose-eligibility",support.sections().get(0).id());
        assertTrue(support.sections().get(0).body().contains("중소기업"));
        assertEquals("purpose-cost",contract.sections().get(0).id());
        assertTrue(contract.sections().get(0).body().contains("33,000원"));
        for(var report:java.util.List.of(business,support,contract)) {
            assertTrue(report.sections().stream().anyMatch(s->s.id().equals("purpose-exceptions")&&s.body().contains("신규 신청자는 제외")));
            for(var section:report.sections())for(var e:section.evidence())assertTrue(doc.blocks().stream().anyMatch(b->b.id().equals(e.blockId())&&b.page()==e.page()&&b.text().contains(e.quote())));
        }
        assertThrows(IllegalArgumentException.class,()->analyzer.analyze(doc,"","local",false,"INVALID"));
        assertEquals(analyzer.analyze(doc,"","local",false).sections(),analyzer.analyze(doc,"","local",false,null).sections());
    }
    @Test void unrelatedPurposeDoesNotInventMissingTerms() throws Exception {
        var doc=documents.normalize(null,"오늘은 화창한 날씨입니다.");
        var report=analyzer.analyze(doc,"","local",false,"CONTRACT");
        assertTrue(report.sections().stream().allMatch(s->s.evidence().isEmpty()&&s.body().contains("찾지 못했습니다")));
    }
    @Test void fiftyPagesAllowedAndFiftyOneRejectedBeforeOcr() throws Exception {
        var accepted=documents.normalize(pdf(50,true),null);
        assertEquals(50,accepted.pages());
        // Blank 51-page PDF would fail with 'no text' if it reached extraction.
        var failure=assertThrows(IllegalArgumentException.class,()->documents.normalize(pdf(51,false),null));
        assertTrue(failure.getMessage().contains("51쪽"));
        assertTrue(failure.getMessage().contains("50쪽까지"));
    }
    private MockMultipartFile pdf(int count,boolean text) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var pdf=new PDDocument()) {
            for(int i=0;i<count;i++) {
                var page=new PDPage();pdf.addPage(page);
                if(text)try(var stream=new PDPageContentStream(pdf,page)) {
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(40,700);stream.showText("Page "+(i+1)+": document summary source");stream.endText();
                }
            }
            pdf.save(bytes);
        }
        return new MockMultipartFile("file","notice.pdf","application/pdf",bytes.toByteArray());
    }
}
