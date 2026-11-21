package ai.actiondoc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;

class PdfLinesTests {
    @Test void sharedFixtureMatchesWeb() throws Exception {
        for(var c:new ObjectMapper().readTree(Path.of("shared/fixtures/line-joining.json").toFile()).path("cases")) {
            var lines=new ArrayList<PdfLines.Line>();
            for(var l:c.path("lines"))lines.add(new PdfLines.Line(l.path("text").asText(),l.path("left").asDouble(),l.path("right").asDouble(),l.path("gap").asDouble()));
            var expected=new ArrayList<String>();c.path("expected").forEach(e->expected.add(e.asText()));
            assertEquals(String.join("\n",expected),PdfLines.join(lines),c.path("name").asText());
        }
    }
    @Test void strippedPdfRejoinsWrappedParagraph() throws Exception {
        try(var pdf=new PDDocument()) {
            var page=new PDPage();pdf.addPage(page);
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try(var out=new PDPageContentStream(pdf,page)) {
                out.setFont(font,12);
                text(out,50,700,"Applicants submit the signed form together with every required document that the program lists");
                text(out,50,684,"stated in the notice before the deadline");
                text(out,50,640,"Eligibility");text(out,520,640,"3");
                text(out,50,624,"Procedure");text(out,520,624,"7");
            }
            var lines=LayoutTextStripper.lines(pdf,1);
            assertEquals("Applicants submit the signed form together with every required document that the program lists stated in the notice before the deadline\nEligibility 3\nProcedure 7",PdfLines.join(lines));
        }
    }
    private static void text(PDPageContentStream out,float x,float y,String s) throws Exception {out.beginText();out.newLineAtOffset(x,y);out.showText(s);out.endText();}
}
