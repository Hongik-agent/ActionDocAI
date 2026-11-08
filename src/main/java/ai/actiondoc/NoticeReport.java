package ai.actiondoc;
import static ai.actiondoc.Models.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Organizes labelled source clauses; never presents keyword hits as an AI summary. */
public final class NoticeReport {
    public record Draft(String type,String title,String overview,List<ReportSection> sections,String periodStatus) {}
    private final List<Block> blocks;
    private NoticeReport(Document doc){
        List<Block> main=new ArrayList<>();
        for(Block b:doc.blocks()) { if(b.text().matches(".*\\[서식\\s*\\d+\\].*"))break; if(!b.text().matches("-\\s*\\d+\\s*-"))main.add(b); }
        blocks=main;
    }
    public static boolean isNotice(Document doc){String all=doc.blocks().stream().map(Block::text).reduce("",(a,b)->a+b).replaceAll("\\s+","");return all.contains("공고")&&(all.contains("모집")||all.contains("지원사업"))&&(all.contains("신청기간")||all.contains("신청대상")||all.contains("사업대상"));}
    public static Draft build(Document doc){return new NoticeReport(doc).build();}
    private static String header(String text){return text.replaceAll("\\s+","").replaceFirst("^[□○●▸*\\p{Co}-]+","");}
    private List<Block> field(int maximum,String... aliases){
        for(int i=0;i<blocks.size();i++){
            String h=header(blocks.get(i).text());boolean match=Arrays.stream(aliases).anyMatch(h::startsWith);if(!match)continue;
            boolean parent=blocks.get(i).text().startsWith("□");
            List<Block> out=new ArrayList<>();out.add(blocks.get(i));
            for(int j=i+1;j<blocks.size();j++){
                String t=blocks.get(j).text();
                if(t.startsWith("□")||(!parent&&t.startsWith("○"))||t.matches("\\d+\\s*(사업개요|자격요건|신청안내|선정기준.*|유의.*)")||t.startsWith("<반드시")||t.startsWith("◎")||t.matches("^[\\p{Co}].*")||header(t).equals("신청자격제한사항")||header(t).equals("비밀보장의원칙"))break;
                out.add(blocks.get(j));
            }
            return out;
        }return List.of();
    }
    private List<Block> contains(int maximum,String regex){
        Pattern p=Pattern.compile(regex);for(int i=0;i<blocks.size();i++)if(p.matcher(blocks.get(i).text()).find())return List.copyOf(blocks.subList(i,Math.min(i+maximum,blocks.size())));return List.of();
    }
    @SafeVarargs private final List<Block> combine(List<Block>... lists){Map<String,Block> unique=new LinkedHashMap<>();for(var list:lists)for(var b:list)unique.putIfAbsent(b.id(),b);return List.copyOf(unique.values());}
    private String clean(String t){
        return t.replaceFirst("^[□○●▸*①-⑳➀-➉]+\\s*","").replaceFirst("^-\\s+","").replaceAll("(?<=\\D)\\d\\)(?=\\s|$)","").replaceAll("\\s+"," ").strip();
    }
    private ReportSection section(String id,String title,List<Block> source){
        StringBuilder body=new StringBuilder();
        for(Block b:source){String text=clean(b.text());if(text.isBlank()||text.matches("[-─]+")||text.matches("[\\p{Co}]+")||Set.of("대출한도","대상주택","제출서류 비고").contains(text))continue;
            boolean newPoint=b.text().matches("^[□○●▸*※①-⑳➀-➉-].*")||b.text().matches("^\\d+\\.\\s+.*")||b.text().matches("^(일반청년|의무복무|제대군인|\\d+년).*복무.*");
            if(body.length()>0)body.append(newPoint?"\n":" ");body.append(text);
        }
        return new ReportSection(id,title,body.toString(),source.stream().map(b->new Evidence(b.id(),b.page(),b.text())).toList(),false);
    }
    private Draft build(){
        String title="지원사업 모집 공고";var name=field(1,"사업명:","사업명：");
        if(!name.isEmpty()){String text=name.get(0).text();int colon=Math.max(text.indexOf(':'),text.indexOf('：'));if(colon>=0)title=text.substring(colon+1).strip();}
        var period=field(4,"신청기간:","접수기간:","모집기간:","신청기간：");
        String periodStatus="UNKNOWN";DateWindow window=null;
        if(!period.isEmpty()){window=DateWindow.parse(period.get(0).text());if(window!=null){LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));periodStatus=today.isAfter(window.end())?"CLOSED":today.isBefore(window.start())?"UPCOMING":"OPEN";}}
        List<ReportSection> sections=new ArrayList<>();
        if(!period.isEmpty())sections.add(section("period","신청 기간과 주요 일정",combine(period,field(3,"결과발표:","선정결과:"))));
        var support=combine(field(4,"대출한도"),field(3,"인천시지원금리:","지원금리:","지원금액:","지원내용:"),contains(1,"본인부담\\s*대출금리"),contains(1,"부대비용은.*본인 부담"),field(4,"대출기간:"),contains(3,"^(?:[①-⑳]\\s*)?.*(?:최대\\s*\\d+\\s*회기).*"),contains(1,"최종.*맞춤.*프로그램.*제공"));
        if(!support.isEmpty())sections.add(section("benefit","지원 내용",support));
        var target=combine(field(3,"사업대상:","지원대상:","대상:"),field(20,"신청자격"),field(4,"(소득)"),field(6,"대상주택"));
        if(target.isEmpty())target=field(12,"신청대상:");
        if(!target.isEmpty())sections.add(section("eligibility","지원 대상",target));
        var method=combine(field(4,"신청방법:","접수방법:"),field(2,"취급은행:"));
        if(!method.isEmpty())sections.add(section("method","신청 방법",method));
        var documents=field(24,"제출서류:","구비서류:");
        if(!documents.isEmpty())sections.add(section("documents","제출 서류",documents));
        var risks=combine(contains(2,"대출추천서.*발급.*이후.*신규"),field(8,"제외대상자"),field(3,"추천유효기간"),field(4,"선정기준"),field(14,"신청자격제한사항"),field(60,"안내사항"));
        if(!risks.isEmpty())sections.add(section("conditions","참여 제한·취소 조건",risks));
        String overview="지원 대상, 지원 내용과 신청 절차를 확인하는 모집 공고입니다. 본인에게 적용되는 조건은 별도 확인이 필요합니다.";
        if(window!=null){overview="공고에 적힌 신청 기간은 "+window.start()+" ~ "+window.end()+"입니다. "+(periodStatus.equals("CLOSED")?"이 접수 기간은 이미 종료됐습니다. 아래는 해당 공고의 지원 조건입니다.":"아래 조건과 신청 절차를 확인해 주세요.");}
        return new Draft("PUBLIC_NOTICE",title,overview,List.copyOf(sections),periodStatus);
    }
    public record DateWindow(LocalDate start,LocalDate end){
        private static final Pattern START=Pattern.compile("(20\\d{2})[.년/\\-]\\s*(\\d{1,2})[.월/\\-]\\s*(\\d{1,2})(?:일|\\.)?");
        public static DateWindow parse(String text){
            Matcher m=START.matcher(text);if(!m.find())return null;
            try {
                int year=Integer.parseInt(m.group(1)),month=Integer.parseInt(m.group(2));LocalDate start=LocalDate.of(year,month,Integer.parseInt(m.group(3)));
                int tilde=text.indexOf('~',m.end());if(tilde<0)tilde=text.indexOf('∼',m.end());if(tilde<0)return null;
                String tail=text.substring(tilde+1).strip();Matcher full=START.matcher(tail);LocalDate end;
                if(full.lookingAt())end=LocalDate.of(Integer.parseInt(full.group(1)),Integer.parseInt(full.group(2)),Integer.parseInt(full.group(3)));
                else {Matcher shortDate=Pattern.compile("(?:(\\d{1,2})[.월/]\\s*)?(\\d{1,2})(?:일|\\.)?\\s*(?:\\(|$|\\s)").matcher(tail);if(!shortDate.find()||shortDate.start()!=0)return null;end=LocalDate.of(year,shortDate.group(1)==null?month:Integer.parseInt(shortDate.group(1)),Integer.parseInt(shortDate.group(2)));}
                return end.isBefore(start)?null:new DateWindow(start,end);
            }catch(Exception e){return null;}
        }
    }
}
