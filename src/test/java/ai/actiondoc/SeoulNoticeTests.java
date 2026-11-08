package ai.actiondoc;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class SeoulNoticeTests {
 @Test void realSeoulNoticeHasMeaningfulGroupsAndNoMisleadingTasks() throws Exception{
  var source=Path.of(System.getProperty("user.home"),"Downloads","2026년 서울시 청년 마음건강 지원사업 4차 참여자 모집 공고_.pdf");
  Assumptions.assumeTrue(Files.exists(source));
  var doc=new DocumentService(new OcrService("")).normalize(new MockMultipartFile("file",source.getFileName().toString(),"application/pdf",Files.readAllBytes(source)),null);
  var report=new AnalysisService(new ObjectMapper()).analyze(doc,"","local",false);
  assertEquals("PUBLIC_NOTICE",report.documentType());assertEquals("CLOSED",report.periodStatus());
  assertTrue(report.findings().isEmpty());assertFalse(report.title().contains("2498"));
  var byId=new HashMap<String,Models.ReportSection>();for(var s:report.sections())byId.put(s.id(),s);
  assertTrue(byId.keySet().containsAll(Set.of("period","benefit","eligibility","method","documents","conditions")));
  String period=byId.get("period").body();assertTrue(period.contains("9월 7일"));assertTrue(period.contains("9월 10일"));assertTrue(period.contains("9월 11일"));assertFalse(period.contains("8. 28"));
  assertTrue(byId.get("benefit").body().contains("최대 6회기"));
  assertTrue(byId.get("eligibility").body().contains("서울 거주"));assertTrue(byId.get("eligibility").body().contains("42세"));
  assertTrue(byId.get("documents").body().contains("없음"));
  assertTrue(byId.get("conditions").body().contains("당일 취소 2회"));assertTrue(byId.get("conditions").body().contains("7일"));
  assertFalse(report.sections().stream().anyMatch(s->s.body().contains("무료")));assertTrue(report.sections().stream().noneMatch(Models.ReportSection::aiRefined));
  for(var section:report.sections())for(var evidence:section.evidence())assertTrue(doc.blocks().stream().anyMatch(b->b.id().equals(evidence.blockId())&&b.text().equals(evidence.quote())));
 }
 @Test void announcementIdsAndYearOnlyTitlesAreNeverDates()throws Exception{
  var doc=new DocumentService().normalize(null,"서울특별시 공고 제2026-2498호\n2026년 서울시 청년 마음건강 지원사업\n많은 관심과 참여를 바랍니다.\n2026. 8. 28.");
  var report=new AnalysisService(new ObjectMapper()).analyze(doc,"","local",false);
  assertTrue(report.findings().stream().noneMatch(f->f.category().equals("date")||f.category().equals("action")));
 }
 @Test void unrelatedDatesCannotProduceApplicationPeriod(){
  assertNull(NoticeReport.DateWindow.parse("서울특별시 공고 제2026-2498호"));
  var range=NoticeReport.DateWindow.parse("신청기간: 2026년 9월 7일(월) 10:00 ~ 9월 10일(목) 17:00");
  assertEquals(java.time.LocalDate.of(2026,9,10),range.end());
 }
}
