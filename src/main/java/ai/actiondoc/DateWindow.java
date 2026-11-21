package ai.actiondoc;
import java.time.*;
import java.util.regex.*;

/** An application window such as '신청기간: 2027년 3월 2일 ~ 3월 5일'; null when the text holds no complete range. */
record DateWindow(LocalDate start,LocalDate end){
    // Range marks: ASCII tilde, tilde operator, fullwidth tilde, wave dash.
    static final String RANGE="[~∼～〜]";
    private static final Pattern START=Pattern.compile("(20\\d{2})[.년/\\-]\\s*(\\d{1,2})[.월/\\-]\\s*(\\d{1,2})(?:일|\\.)?");
    public static DateWindow parse(String text){
        Matcher m=START.matcher(text);if(!m.find())return null;
        try {
            int year=Integer.parseInt(m.group(1)),month=Integer.parseInt(m.group(2));LocalDate start=LocalDate.of(year,month,Integer.parseInt(m.group(3)));
            Matcher tilde=Pattern.compile(RANGE).matcher(text);if(!tilde.find(m.end()))return null;
            String tail=text.substring(tilde.end()).strip();Matcher full=START.matcher(tail);LocalDate end;
            if(full.lookingAt())end=LocalDate.of(Integer.parseInt(full.group(1)),Integer.parseInt(full.group(2)),Integer.parseInt(full.group(3)));
            else {Matcher shortDate=Pattern.compile("(?:(\\d{1,2})[.월/]\\s*)?(\\d{1,2})(?:일|\\.)?\\s*(?:\\(|$|\\s)").matcher(tail);if(!shortDate.find()||shortDate.start()!=0)return null;end=LocalDate.of(year,shortDate.group(1)==null?month:Integer.parseInt(shortDate.group(1)),Integer.parseInt(shortDate.group(2)));}
            return end.isBefore(start)?null:new DateWindow(start,end);
        }catch(Exception e){return null;}
    }
}
