package ai.actiondoc;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
class GovernmentNoticeRegressionTests {
    @Test void realNotice() throws Exception {
        Path source=Path.of(System.getProperty("user.home"),"Downloads","최종_모집_공고문(2026년_청년_주택임차보증금_이자_지원_사업대상자_모집_공고).pdf");
        Assumptions.assumeTrue(Files.exists(source));
        var document=new DocumentService(new OcrService("")).normalize(new MockMultipartFile("file",source.getFileName().toString(),"application/pdf",Files.readAllBytes(source)),null);
        String output=System.getProperty("actiondoc.regressionOutput");
        if(output!=null)Files.writeString(Path.of(output),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(document));
        Assertions.assertEquals(13,document.pages());
        var report=new AnalysisService(new ObjectMapper()).analyze(document,"","local",false);
        Assertions.assertEquals("PUBLIC_NOTICE",report.documentType());
        Assertions.assertEquals("CLOSED",report.periodStatus());
        Assertions.assertTrue(report.findings().isEmpty(),"Expired notice must not propose tasks");
        String body=report.sections().stream().map(ai.actiondoc.Models.ReportSection::body).reduce("",(a,b)->a+b);
        for(String expected:java.util.List.of("1억원","3.5%","3.0%","6천만원","8천만원","가족관계증명서","11.(월)","29.(금)"))Assertions.assertTrue(body.contains(expected),expected);
        for(var section:report.sections())for(var evidence:section.evidence())Assertions.assertTrue(document.blocks().stream().anyMatch(b->b.id().equals(evidence.blockId())&&b.text().contains(evidence.quote())));
        if(output!=null)Files.writeString(Path.of(output+".report.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
