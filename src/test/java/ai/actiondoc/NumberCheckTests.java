package ai.actiondoc;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NumberCheckTests {
    @Test void sharedFixtureMatchesWeb() throws Exception {
        var fixture=new ObjectMapper().readTree(Path.of("shared/fixtures/numbers.json").toFile());
        for(var c:fixture.path("accept"))assertTrue(NumberCheck.missing(c.path("statement").asText(),join(c)).isEmpty(),c.toString());
        for(var c:fixture.path("reject"))assertFalse(NumberCheck.missing(c.path("statement").asText(),join(c)).isEmpty(),c.toString());
    }
    private static String join(JsonNode c){var out=new java.util.ArrayList<String>();c.path("sources").forEach(s->out.add(s.asText()));return String.join("\n",out);}
}
