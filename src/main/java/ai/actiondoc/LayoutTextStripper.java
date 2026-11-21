package ai.actiondoc;

import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.*;

/** Collects one {@link PdfLines.Line} per laid-out text line; PDFBox already inserts the word spaces. */
final class LayoutTextStripper extends PDFTextStripper {
    private final List<PdfLines.Line> lines=new ArrayList<>();
    private StringBuilder text=new StringBuilder();
    private double left,right,gap;

    private LayoutTextStripper() throws IOException {setSortByPosition(true);}

    static List<PdfLines.Line> lines(PDDocument pdf,int page) throws IOException {
        var stripper=new LayoutTextStripper();
        stripper.setStartPage(page);stripper.setEndPage(page);
        stripper.getText(pdf);
        stripper.writeLineSeparator();
        return stripper.lines;
    }
    @Override protected void writeString(String string,List<TextPosition> positions) {
        if(positions.isEmpty())return;
        double start=Double.MAX_VALUE,end=-Double.MAX_VALUE,size=0;
        for(var p:positions) {start=Math.min(start,p.getXDirAdj());end=Math.max(end,p.getXDirAdj()+p.getWidthDirAdj());size=Math.max(size,p.getFontSizeInPt());}
        if(text.isEmpty())left=start;else gap=Math.max(gap,(start-right)/(size>0?size:10));
        right=Math.max(right,end);
        text.append(string);
    }
    @Override protected void writeWordSeparator() {if(!text.isEmpty())text.append(' ');}
    @Override protected void writeLineSeparator() {
        if(!text.toString().isBlank())lines.add(new PdfLines.Line(text.toString(),left,right,gap));
        text=new StringBuilder();left=right=gap=0;
    }
}
