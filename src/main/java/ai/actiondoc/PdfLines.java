package ai.actiondoc;

import java.util.*;
import java.util.regex.Pattern;

/** Rebuilds paragraphs from PDF layout lines; same rules as joinLines in web/src/summary.js. */
final class PdfLines {
    /** gap is the widest horizontal gap inside the line, in glyph sizes. */
    record Line(String text,double left,double right,double gap) {}
    private static final Pattern LIST_MARKER=Pattern.compile("^(?:[□■○●◦▪◆◇※*☞▶►•·ㅇ\\-–—]|[①-⑳➀-➉]|\\(?\\d{1,2}[.)]|[가-하][.)]|[【\\[<〈「『])");
    private static final Pattern SPACED=Pattern.compile("(?U)(?<!\\S)[가-힣](?: [가-힣]){2,}(?=[\\s:：]|$)");
    private static final Pattern CONTINUES=Pattern.compile("[가-힣A-Za-z0-9,·(]$"),ENDS=Pattern.compile("[.!?。]$|다$");

    static String join(List<Line> lines) {
        if(lines.isEmpty())return "";
        double left=lines.stream().mapToDouble(Line::left).min().getAsDouble(),right=lines.stream().mapToDouble(Line::right).max().getAsDouble();
        var out=new ArrayList<String>();
        boolean prevSpaced=false;
        String last="";
        for(int i=0;i<lines.size();i++) {
            var line=lines.get(i);
            String text=line.text().replaceAll("(?U)\\s+"," ").trim();
            var m=SPACED.matcher(text);
            boolean spaced=m.find();
            text=m.replaceAll(r->r.group().replace(" ",""));
            var prev=i>0?lines.get(i-1):null;
            // Prose reaching the right margin continues; a gap of two or more glyphs means columns, which never continue.
            boolean wrapped=prev!=null && !prevSpaced && prev.gap()<2 && line.gap()<2 && !out.isEmpty() && !text.isEmpty()
                && prev.right()>=right-(right-left)*0.12 && CONTINUES.matcher(last).find() && !ENDS.matcher(last).find() && !LIST_MARKER.matcher(text).find();
            if(wrapped){last+=" "+text;out.set(out.size()-1,last);}else{last=text;out.add(text);}
            prevSpaced=spaced;
        }
        return String.join("\n",out);
    }
}
