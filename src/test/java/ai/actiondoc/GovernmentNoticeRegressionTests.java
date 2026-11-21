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
        LocalSummaryTests.assertMatchesBasicSummary(document,report,"GENERAL");
        if(output!=null)Files.writeString(Path.of(output+".report.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
