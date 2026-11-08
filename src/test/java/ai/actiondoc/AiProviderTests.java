package ai.actiondoc;
import static ai.actiondoc.Models.*;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.http.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiProviderTests {
    final ObjectMapper json=new ObjectMapper();
    static final String PROFILE="서울 거주하는 20대 여자이고 직장인이야. 연봉 5000만원이야.";
    static final String AGE="19세 ~ 39세 무주택 청년세대주가 대상입니다.";
    static final String REGION="인천 거주 또는 전입예정자가 대상입니다.";
    static final String INCOME="본인 연소득 6천만원 이하, 기혼은 부부합산 8천만원 이하이며 세전 기준입니다.";
    static final String PERIOD="신청기간: 2099. 5. 11.(월) 10:00 ~ 29.(금) 18:00";
    Document document()throws Exception{return new DocumentService().normalize(null,String.join("\n",AGE,REGION,INCOME,PERIOD));}
    ObjectNode extraction(){
        var out=json.createObjectNode();var facts=out.putArray("facts");
        for(var pair:List.of(new String[]{"eligibility","나이·주택",AGE,"b1"},new String[]{"eligibility","거주",REGION,"b2"},new String[]{"eligibility","소득",INCOME,"b3"},new String[]{"period","신청기간",PERIOD,"b4"})){
            var fact=facts.addObject();fact.put("category",pair[0]);fact.put("topic",pair[1]);fact.put("statement",pair[2]);fact.putArray("evidence").addObject().put("blockId",pair[3]).put("quote",pair[2]);
        }return out;
    }
    ObjectNode report(){
        var r=json.createObjectNode().put("title","청년 지원 모집").put("documentType","PUBLIC_NOTICE").put("overview","무주택 청년의 소득·거주 조건을 확인하는 모집 공고입니다.");r.putArray("overviewFactIds").add("f1").add("f2").add("f3");
        var section=r.putArray("sections").addObject().put("id","s1").put("title","핵심 대상").put("body","19세 ~ 39세 무주택 청년세대주가 대상이며 인천 거주 또는 전입예정 조건을 확인해야 합니다.");section.putArray("factIds").add("f1").add("f2");
        var checks=r.putArray("personalChecks");check(checks,"region","거주·전입","UNKNOWN","서울에 거주한다고 입력했습니다. 인천 전입예정자 예외가 있어 전입 계획을 확인해야 합니다.","서울","f2");
        check(checks,"age","나이","MATCH","입력한 20대는 19세 ~ 39세 범위에 해당하지만 나이 조건에 대한 비교일 뿐입니다.","20대","f1");
        check(checks,"income","소득","UNKNOWN","연봉 5000만원이라고 입력했으나 세전 기준과 기혼·부부합산 소득 여부를 확인해야 합니다.","연봉 5000만원","f3");
        check(checks,"house","주택","UNKNOWN","무주택 및 청년세대주 여부를 입력하지 않아 확인이 필요합니다.","","f1");
        var actions=r.putArray("actions");action(actions,"check","전입 계획과 주택 조건 확인","전입예정 여부와 무주택·세대주 조건을 확인하세요.","CHECK",null,"f1","f2");
        return r;
    }
    void check(ArrayNode arr,String id,String criterion,String status,String reason,String quote,String...refs){var n=arr.addObject().put("id",id).put("criterion",criterion).put("status",status).put("reason",reason).put("profileQuote",quote);var a=n.putArray("factIds");for(var ref:refs)a.add(ref);}
    void action(ArrayNode arr,String id,String title,String detail,String kind,String date,String...refs){var n=arr.addObject().put("id",id).put("title",title).put("detail",detail).put("kind",kind);if(date==null)n.putNull("deadline");else n.put("deadline",date);var a=n.putArray("factIds");for(var ref:refs)a.add(ref);}
    ObjectNode audit(String... rejected){var a=json.createObjectNode();var arr=a.putArray("verdicts");for(var id:List.of("overview","s1","region","age","income","house","check","apply","fact-f1","fact-f2","fact-f3","fact-f4"))arr.addObject().put("id",id).put("supported",!List.of(rejected).contains(id));return a;}
    record Harness(AnalysisService service,List<HttpRequest> requests) {}
    @SuppressWarnings("unchecked") Harness provider(JsonNode... outputs)throws Exception{
        HttpClient client=mock(HttpClient.class);List<HttpRequest> requests=new ArrayList<>();var queue=new ArrayDeque<>(List.of(outputs));
        when(client.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(inv->{requests.add(inv.getArgument(0));if(queue.isEmpty())throw new AssertionError("Unexpected model call");HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",queue.remove().toString())))))));return response;});
        return new Harness(new AnalysisService(json,client,"test-key","test-model"),requests);
    }
    String requestBody(HttpRequest request)throws Exception{var result=new CompletableFuture<String>();var bytes=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}public void onError(Throwable t){result.completeExceptionally(t);}public void onComplete(){result.complete(bytes.toString(StandardCharsets.UTF_8));}});return result.get();}
    @Test void fullPipelineComparesSelfReportedProfileAndSeparatesItFromExtraction()throws Exception{
        var h=provider(extraction(),report(),audit());var result=h.service.analyze(document(),PROFILE,"ai",true);
        assertEquals(3,h.requests.size());assertEquals(4,result.facts().size());assertEquals(4,result.personalChecks().size());assertEquals(3,result.stages().size());
        assertEquals("UNKNOWN",result.personalChecks().get(0).status());assertEquals("MATCH",result.personalChecks().get(1).status());assertEquals(1,result.findings().size());
        assertFalse(requestBody(h.requests.get(0)).contains(PROFILE));assertTrue(requestBody(h.requests.get(1)).contains(PROFILE));assertTrue(requestBody(h.requests.get(2)).contains("sourceBlocks"));
        for(var request:h.requests){var payload=json.readTree(requestBody(request));assertFalse(payload.path("store").asBoolean(true));assertTrue(payload.at("/text/format/strict").asBoolean());}
        String output=System.getProperty("actiondoc.personalFixture");if(output!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(output),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    @Test void fabricatedSourceQuoteNeverBecomesFact()throws Exception{var e=extraction();for(var f:e.path("facts"))((ObjectNode)f.path("evidence").get(0)).put("quote","원문에 없는 지원 조건");assertThrows(IllegalArgumentException.class,()->provider(e).service.analyze(document(),PROFILE,"ai",true));}
    @Test void fabricatedProfileQuoteIsExcluded()throws Exception{var r=report();((ObjectNode)r.path("personalChecks").get(0)).put("profileQuote","인천 거주");var a=provider(extraction(),r,audit()).service.analyze(document(),PROFILE,"ai",true);assertTrue(a.personalChecks().stream().noneMatch(c->c.id().equals("region")));assertTrue(a.warnings().stream().anyMatch(w->w.contains("사용자 입력")));}
    @Test void independentAuditRejectsUnsupportedCertainty()throws Exception{var r=report();((ObjectNode)r.path("personalChecks").get(0)).put("status","MISMATCH").put("reason","서울 거주자는 무조건 신청 불가합니다.");var a=provider(extraction(),r,audit("region")).service.analyze(document(),PROFILE,"ai",true);assertTrue(a.personalChecks().stream().noneMatch(c->c.id().equals("region")));}
    @Test void missingAuditVerdictFailsClosed()throws Exception{var a=audit();((ArrayNode)a.path("verdicts")).remove(1);assertThrows(IllegalArgumentException.class,()->provider(extraction(),report(),a).service.analyze(document(),PROFILE,"ai",true));}
    @Test void unknownEligibilityBlocksApplyButAllowsChecking()throws Exception{var r=report();action((ArrayNode)r.path("actions"),"apply","신청서 제출","신청서를 제출하세요.","APPLY","2099-05-29","f4");var a=provider(extraction(),r,audit()).service.analyze(document(),PROFILE,"ai",true);assertEquals(List.of("check"),a.findings().stream().map(Finding::id).toList());}
    @Test void expiredNoticeBlocksPreparationAndApplication()throws Exception{
        String closed=PERIOD.replace("2099","2020");var doc=new DocumentService().normalize(null,String.join("\n",AGE,REGION,INCOME,closed));var e=extraction();((ObjectNode)e.path("facts").get(3)).put("statement",closed);((ObjectNode)e.path("facts").get(3).path("evidence").get(0)).put("quote",closed);
        var r=report();action((ArrayNode)r.path("actions"),"apply","서류 준비","서류를 준비하세요.","PREPARE",null,"f4");var a=provider(e,r,audit()).service.analyze(doc,PROFILE,"ai",true);assertEquals("CLOSED",a.periodStatus());assertEquals(1,a.findings().size());
    }
    @Test void nonexistentFactReferenceIsNotDisplayed()throws Exception{var r=report();((ArrayNode)r.path("sections").get(0).path("factIds")).removeAll().add("invented");assertThrows(IllegalArgumentException.class,()->provider(extraction(),r,audit()).service.analyze(document(),PROFILE,"ai",true));}
    @Test void noUserContextProducesNoPersonalJudgement()throws Exception{var a=provider(extraction(),report(),audit()).service.analyze(document(),"","ai",true);assertTrue(a.personalChecks().isEmpty());}
    @Test void fabricatedAmountIsRejectedBeforeAudit()throws Exception{var r=report();((ObjectNode)r.path("sections").get(0)).put("body","지원금 999999원을 지급합니다.");assertThrows(IllegalArgumentException.class,()->provider(extraction(),r,audit()).service.analyze(document(),PROFILE,"ai",true));}
    @Test void missingConsentAndKeyDoNotCallProvider()throws Exception{var h=provider();assertThrows(IllegalArgumentException.class,()->h.service.analyze(document(),PROFILE,"ai",false));assertTrue(h.requests.isEmpty());assertThrows(IllegalArgumentException.class,()->new AnalysisService(json,mock(HttpClient.class),"","test-model").analyze(document(),PROFILE,"ai",true));}
    @Test void rejectedExtractedFactAlsoRemovesDependentSummariesAndChecks()throws Exception{var a=provider(extraction(),report(),audit("fact-f3")).service.analyze(document(),PROFILE,"ai",true);assertEquals(3,a.facts().size());assertTrue(a.personalChecks().stream().noneMatch(c->c.id().equals("income")));}
    @Test void oneFactCanKeepConditionsAcrossMultiplePages()throws Exception{var source=document();var blocks=source.blocks().stream().map(b->new Block(b.id(),b.id().equals("b2")?6:1,b.text())).toList();var doc=new Document(source.id(),source.name(),source.type(),6,source.characters(),source.markdown(),blocks,source.warnings(),List.of());var e=extraction();((ArrayNode)e.path("facts").get(0).path("evidence")).addObject().put("blockId","b2").put("quote",REGION);var a=provider(e,report(),audit()).service.analyze(doc,PROFILE,"ai",true);assertEquals(List.of(1,6),a.facts().get(0).evidence().stream().map(Evidence::page).toList());}
    @Test void changingCurrencyUnitWithSameNumberIsRejected(){assertFalse(LlmDocumentAnalyzer.numbersSupported("1억원 지원",List.of(new Evidence("b1",1,"1만원 지원"))));assertTrue(LlmDocumentAnalyzer.numbersSupported("최대 1억원",List.of(new Evidence("b1",1,"최대 1억원까지 대출 가능"))));}
    @Test void longDocumentIsFullyExtractedBeforeOneCrossPartSynthesis()throws Exception{
        var blocks=new ArrayList<Block>();blocks.add(new Block("b1",1,AGE));blocks.add(new Block("padding",2,"가".repeat(32000-AGE.length())));blocks.add(new Block("b2",6,REGION));blocks.add(new Block("b3",6,INCOME));blocks.add(new Block("b4",7,PERIOD));
        var doc=new Document("long","긴 문서","PDF",7,33000,"",blocks,List.of(),List.of());var all=extraction();var first=json.createObjectNode();first.putArray("facts").add(all.path("facts").get(0));var second=json.createObjectNode();var remainder=second.putArray("facts");for(int i=1;i<4;i++)remainder.add(all.path("facts").get(i));var h=provider(first,second,report(),audit());var result=h.service.analyze(doc,PROFILE,"ai",true);assertEquals(2,result.chunks());assertEquals(4,result.facts().size());assertEquals(4,h.requests.size());var synthesis=json.readTree(requestBody(h.requests.get(2))).path("input").asText();assertTrue(synthesis.contains(AGE));assertTrue(synthesis.contains(REGION));
    }
    @Test void effectiveDateCannotBecomeActionDeadline(){assertFalse(LlmDocumentAnalyzer.supportedDeadline("2026-12-01",List.of(new Evidence("b1",1,"2026년 12월 1일부터 적용됩니다."))));assertTrue(LlmDocumentAnalyzer.supportedDeadline("2099-05-29",List.of(new Evidence("b1",1,PERIOD))));}
}
