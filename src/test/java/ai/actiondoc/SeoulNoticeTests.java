package ai.actiondoc;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class SeoulNoticeTests {
 @Test void realSeoulNoticeGetsTheBasicSummary() throws Exception{
  var source=Path.of(System.getProperty("user.home"),"Downloads","2026년 서울시 청년 마음건강 지원사업 4차 참여자 모집 공고_.pdf");
  Assumptions.assumeTrue(Files.exists(source));
  var doc=new DocumentService(new OcrService("")).normalize(new MockMultipartFile("file",source.getFileName().toString(),"application/pdf",Files.readAllBytes(source)),null);
  var analyzer=new AnalysisService(new ObjectMapper());
  for(String purpose:BasicSummary.PURPOSE_TITLES.keySet())LocalSummaryTests.assertMatchesBasicSummary(doc,analyzer.analyze(doc,"","local",false,purpose),purpose);
 }
 @Test void announcementLinesAreNotTasks()throws Exception{
  var doc=new DocumentService().normalize(null,"가나시 공고 제2027-1234호\n2027년 가나시 청년 지원사업\n많은 관심과 참여를 바랍니다.\n2027. 1. 5.");
  var report=new AnalysisService(new ObjectMapper()).analyze(doc,"","local",false);
  assertTrue(report.findings().isEmpty());
 }
 @Test void unrelatedDatesCannotProduceApplicationPeriod(){
  assertNull(DateWindow.parse("가나시 공고 제2027-1234호"));
  var range=DateWindow.parse("신청기간: 2027년 3월 2일(화) 10:00 ~ 3월 5일(금) 17:00");
  assertEquals(java.time.LocalDate.of(2027,3,2),range.start());assertEquals(java.time.LocalDate.of(2027,3,5),range.end());
 }
}
