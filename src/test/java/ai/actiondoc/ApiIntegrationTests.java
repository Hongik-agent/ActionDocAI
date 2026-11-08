package ai.actiondoc;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockHttpSession;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired jakarta.servlet.MultipartConfigElement uploadConfig;
    static final Path DB;
    static { try { DB=Files.createTempDirectory("actiondoc-test-").resolve("tasks.db"); } catch(Exception e) {throw new RuntimeException(e);} }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("actiondoc.db",()->DB.toString()); }
    @Test void documentProgressBelongsToTheUploadingSessionAndReportsFailure() throws Exception {
        var initial=mvc.perform(get("/api/config")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        String token=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        String id=java.util.UUID.randomUUID().toString();
        mvc.perform(multipart("/api/documents").param("text","테스트 본문").param("progressId",id).session(session).header("X-CSRF-Token",token)).andExpect(status().isOk());
        mvc.perform(get("/api/documents/progress/"+id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("COMPLETE"));
        mvc.perform(get("/api/documents/progress/"+id).session(new MockHttpSession())).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("WAITING"));
        mvc.perform(get("/api/documents/progress/invalid").session(session)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documents").file(HwpDocumentTests.upload(new byte[]{1,2,3})).param("progressId",id).session(session).header("X-CSRF-Token",token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/progress/"+id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("FAILED"));
    }
    @Test void summaryPurposePassesThroughApiAndRejectsUnknownValues() throws Exception {
        var initial=mvc.perform(get("/api/config")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        String token=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        var uploaded=mvc.perform(multipart("/api/documents").param("text","담당 부서: 운영팀. 보고서를 제출해 주세요.").session(session).header("X-CSRF-Token",token)).andReturn();
        String docId=json.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
        var body=json.createObjectNode().put("documentId",docId).put("mode","local").put("consent",false).put("purpose","BUSINESS");
        mvc.perform(post("/api/analyses").session(session).header("X-CSRF-Token",token).contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].id").value("purpose-requests"));
        body.put("purpose","UNKNOWN");
        mvc.perform(post("/api/analyses").session(session).header("X-CSRF-Token",token).contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
    }
    @Test void advertisedAndHttpUploadLimitsBothAllowFiftyMb() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.maxFileMb").value(50)).andExpect(jsonPath("$.maxPdfPages").value(50));
        Assertions.assertEquals(50L * 1024 * 1024, uploadConfig.getMaxFileSize());
        Assertions.assertEquals(51L * 1024 * 1024, uploadConfig.getMaxRequestSize());
        var response = new ApiErrors().large();
        Assertions.assertEquals(413, response.getStatusCode().value());
        Assertions.assertEquals("파일은 50MB 이하여야 합니다.", ((java.util.Map<?,?>)response.getBody()).get("error"));
    }
    @Test void hwpUploadAnalysisAndApprovedTaskUseTheSameWorkflow() throws Exception {
        var initial=mvc.perform(get("/api/config")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        String token=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        var uploaded=mvc.perform(multipart("/api/documents").file(HwpDocumentTests.upload(HwpDocumentTests.bytes(HwpDocumentTests.notice(true)))).session(session).header("X-CSRF-Token",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("HWP")).andExpect(jsonPath("$.pages").value(0)).andReturn();
        String docId=json.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
        var analyzed=mvc.perform(post("/api/analyses").session(session).header("X-CSRF-Token",token).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("documentId",docId,"mode","local","context","","consent",false))))
                .andExpect(status().isOk()).andReturn();
        var analysis=json.readTree(analyzed.getResponse().getContentAsString());
        JsonNode action=null;
        for(var finding:analysis.path("findings"))if(finding.path("category").asText().equals("action"))action=finding;
        Assertions.assertNotNull(action);
        var body=json.createObjectNode().put("analysisId",analysis.path("id").asText()).put("findingId",action.path("id").asText()).put("title","HWP 서류 제출").put("description","공문 원문 확인").put("dueDate","2026-11-25").put("approved",true);
        mvc.perform(post("/api/tasks").session(session).header("X-CSRF-Token",token).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.sourceName").value("공문.HWP"));
        mvc.perform(multipart("/api/documents").file(HwpDocumentTests.upload(new byte[]{1,2,3})).session(session).header("X-CSRF-Token",token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
    }
    @Test void approvalFlowAndForgeryRejection() throws Exception {
        var initial=mvc.perform(get("/api/config")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();String token=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        mvc.perform(multipart("/api/documents").param("text","hello").session(session)).andExpect(status().isForbidden());
        var uploaded=mvc.perform(multipart("/api/documents").param("text","2026년 11월 25일까지 신청서를 제출해 주세요.").session(session).header("X-CSRF-Token",token)).andExpect(status().isOk()).andReturn();
        String docId=json.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
        var analyzed=mvc.perform(post("/api/analyses").session(session).header("X-CSRF-Token",token).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("documentId",docId,"mode","local","context","","consent",false)))).andExpect(status().isOk()).andReturn();
        JsonNode analysis=json.readTree(analyzed.getResponse().getContentAsString());String findingId="";
        for(var f:analysis.path("findings"))if(f.path("category").asText().equals("action"))findingId=f.path("id").asText();
        var body=json.createObjectNode().put("analysisId",analysis.path("id").asText()).put("findingId",findingId).put("title","신청서 제출").put("description","내용 확인").put("dueDate","2026-11-25").put("approved",false);
        mvc.perform(post("/api/tasks").session(session).header("X-CSRF-Token",token).contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        body.put("approved",true);
        var saved=mvc.perform(post("/api/tasks").session(session).header("X-CSRF-Token",token).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn();
        String taskId=json.readTree(saved.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(patch("/api/tasks/"+taskId).session(session).header("X-CSRF-Token",token).contentType("application/json").content("{\"status\":\"DONE\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("DONE"));
        mvc.perform(post("/api/analyses").session(new MockHttpSession()).header("X-CSRF-Token",token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/config").header("Host","evil.example")).andExpect(status().isForbidden());
    }
}
