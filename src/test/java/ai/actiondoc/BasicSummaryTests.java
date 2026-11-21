package ai.actiondoc;

import static ai.actiondoc.Models.Block;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BasicSummaryTests {
    @Test void sharedFixtureMatchesWeb() throws Exception {
        for(var c:new ObjectMapper().readTree(Path.of("shared/fixtures/basic-summary.json").toFile()).path("cases")) {
            String name=c.path("name").asText();
            var sections=BasicSummary.summarize(blocksFromText(c.path("text").asText(),c.path("page").asInt()),c.path("purpose").asText());
            var expected=new LinkedHashMap<String,List<String>>();
            c.path("expect").fields().forEachRemaining(e->expected.put(e.getKey(),texts(e.getValue())));
            var actual=new LinkedHashMap<String,List<String>>();
            for(var s:sections)actual.put(s.id(),s.excerpts().stream().map(Block::text).toList());
            assertEquals(List.copyOf(expected.entrySet()),List.copyOf(actual.entrySet()),name);
            c.path("evidence").fields().forEachRemaining(e->assertEquals(texts(e.getValue()),section(sections,e.getKey()).evidence().stream().map(Block::text).toList(),name));
            c.path("evidenceExcludes").fields().forEachRemaining(e->{
                var re=Pattern.compile(e.getValue().asText());
                assertTrue(section(sections,e.getKey()).evidence().stream().noneMatch(b->re.matcher(b.text()).find()),name);
            });
        }
    }
    @Test void rejectsUnknownPurpose() {
        var e=assertThrows(IllegalArgumentException.class,()->BasicSummary.summarize(blocksFromText("본문입니다.",0),"WRONG"));
        assertEquals("요약 목적을 다시 선택해 주세요.",e.getMessage());
        assertEquals("지원사업·모집 공고",BasicSummary.PURPOSE_TITLES.get("SUPPORT"));
    }
    /** Same as blocksFromText in web/src/summary.js. */
    private static List<Block> blocksFromText(String text,int page) {
        var out=new ArrayList<Block>();
        for(String line:text.replace("\u0000","").split("\r?\n|\r")) {
            String t=BasicSummary.trim(line.replaceAll("[\\t\\f ]+"," "));
            if(!t.isEmpty())out.add(new Block("b"+(out.size()+1),page,t));
        }
        return out;
    }
    private static List<String> texts(com.fasterxml.jackson.databind.JsonNode n){var out=new ArrayList<String>();n.forEach(t->out.add(t.asText()));return out;}
    private static BasicSummary.Section section(List<BasicSummary.Section> sections,String id){return sections.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();}
}
