package ai.actiondoc;
import static ai.actiondoc.Models.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.io.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.action.*;
class ActionRoutesTests {
 Analysis analyze(Document d){return new AnalysisService(new ObjectMapper()).analyze(d,"","local",false);}
 Document document(String text)throws Exception{return new DocumentService(new OcrService("")).normalize(null,text);}
 @Test void sourceDestinationsHaveEvidenceAndPortalNavigation()throws Exception{
  var d=document("○ 신청방법: 청년몽땅정보통(https://youth.seoul.go.kr)에서 온라인 신청\n[금융·복지] → [건강지원] → [서울시 청년 마음건강 지원]\n문의 이메일: help@example.org\n문의처: 다산콜센터(☎02-120)\nFAQ: https://youth.seoul.go.kr");
  var r=analyze(d);assertEquals(3,r.actionRoutes().size());
  var web=r.actionRoutes().stream().filter(x->x.kind().equals("WEB")).findFirst().orElseThrow();
  assertEquals("https://youth.seoul.go.kr/",web.url());assertTrue(web.navigation().contains("건강지원"));assertEquals(3,web.evidence().size());
  assertTrue(r.actionRoutes().stream().anyMatch(x->x.url().equals("mailto:help@example.org")));
  assertTrue(r.actionRoutes().stream().anyMatch(x->x.url().equals("tel:02120")));
  for(var route:r.actionRoutes())for(var e:route.evidence())assertTrue(d.blocks().stream().anyMatch(b->b.id().equals(e.blockId())&&b.text().equals(e.quote())));
 }
 @Test void expiredNoticesKeepReferenceLinksWithoutApplyPrompt()throws Exception{
  var r=analyze(document("참여자 모집 공고\n○ 사업명: 서비스 지원\n○ 신청기간: 2020년 9월 7일 ~ 9월 10일\n○ 신청방법: https://youth.seoul.go.kr 온라인 신청"));
  assertEquals("CLOSED",r.periodStatus());var link=r.actionRoutes().get(0);assertEquals("REFERENCE",link.status());assertEquals("2020-09-10",link.applicationEndsOn());assertFalse(link.title().contains("신청"));assertTrue(link.hint().contains("종료"));
 }
 @Test void noInventedDeepLinkAndQueryEncodingIsPreserved()throws Exception{
  var r=analyze(document("신청 사이트: youth.incheon.go.kr\n신청: https://example.org/apply?id=a%2Fb&code=3#step2"));
  assertEquals("https://youth.incheon.go.kr/",r.actionRoutes().get(0).url());assertEquals("https://example.org/apply?id=a%2Fb&code=3#step2",r.actionRoutes().get(1).url());
 }
 @Test void unsupportedSchemesCredentialsAndLocalServicesAreRejected()throws Exception{
  for(String u:List.of("javascript:alert(1)","file:///tmp/test","https://user:pass@example.org/path","http://127.0.0.1:8080","http://host.local/","http://router.internal/","http://example.org:9000/"))assertNull(ActionRoutes.webTarget(u),u);
  var r=analyze(document("javascript:https://evil.org\nfile:///example.org\nhttp://127.0.0.1:8080\nhttps://user:pass@evil.org/path"));assertTrue(r.actionRoutes().isEmpty());
 }
 @Test void inquiryNumbersAndMailDoNotCarryPersonalFormFieldsOrHeaders()throws Exception{
  assertTrue(analyze(document("신청자 연락처: 010-1234-5678\n신청서 이름: 홍길동")).actionRoutes().isEmpty());
  var r=analyze(document("이메일 문의: help@example.org?subject=private&body=secret\n문의 전화: 02-6358-0642~6"));
  assertTrue(r.actionRoutes().stream().anyMatch(x->x.url().equals("mailto:help@example.org")));
  var phone=r.actionRoutes().stream().filter(x->x.kind().equals("PHONE")).findFirst().orElseThrow();assertEquals("tel:0263580642",phone.url());assertTrue(phone.displayAddress().endsWith("~6"));assertTrue(phone.hint().contains("첫 번째"));
 }
 @Test void pdfHyperlinkAnnotationsAreExposedEvenWithoutVisibleUrl()throws Exception{
  byte[] bytes;
  try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
   var page=new PDPage();pdf.addPage(page);try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Open application page");content.endText();}
   var link=new PDAnnotationLink();link.setRectangle(new PDRectangle(50,690,200,30));var action=new PDActionURI();action.setURI("https://example.org/apply/entry");link.setAction(action);page.getAnnotations().add(link);pdf.save(out);bytes=out.toByteArray();
  }
  var d=new DocumentService(new OcrService("")).normalize(new MockMultipartFile("file","hyperlink.pdf","application/pdf",bytes),null);
  var r=analyze(d);assertEquals("https://example.org/apply/entry",r.actionRoutes().get(0).url());assertTrue(r.actionRoutes().get(0).evidence().get(0).quote().contains("PDF 연결 주소"));
 }
 @Test void sourceWithoutDestinationProducesNoGeneratedLinks()throws Exception{assertTrue(analyze(document("자세한 내용은 담당 기관에 문의하세요. 신청 링크는 별도로 안내합니다.")).actionRoutes().isEmpty());}
}
