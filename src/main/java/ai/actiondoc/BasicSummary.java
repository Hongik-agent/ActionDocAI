package ai.actiondoc;

import static ai.actiondoc.Models.Block;
import java.util.*;
import java.util.regex.Pattern;

/** Free summary that quotes source lines; a port of summarize() in web/src/summary.js, checked by shared/fixtures/basic-summary.json. */
final class BasicSummary {
    record Section(String id,String title,List<Block> evidence,List<Block> excerpts) {}
    private record Rule(String id,String title,Pattern pattern) {}
    private record Fact(Block block,int score) {}
    private record Entry(Block block,int rank) {}

    // JS \s (and what JS trim() strips). Java's \s is ASCII only, and (?U) would also widen \d and \w.
    private static final String WS="\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    /** Compiles a JS regex source with JS meanings: \s and \S, '.' (no U+0085 line break) and '$' as the end of input only. */
    private static Pattern re(String js,int... flags) {
        return Pattern.compile(js.replace("\\S","[^"+WS+"]").replace("\\s","["+WS+"]").replace(".?","[^\\n\\r\\u2028\\u2029]?").replace("$","\\z"),flags.length>0?flags[0]:0);
    }
    private static Rule rule(String id,String title,String js){return new Rule(id,title,re(js));}

    static final Map<String,String> PURPOSE_TITLES;
    static {
        var titles=new LinkedHashMap<String,String>();
        titles.put("GENERAL","전체 정리");titles.put("BUSINESS","공문·업무 요청");titles.put("SUPPORT","지원사업·모집 공고");titles.put("CONTRACT","요금·계약 안내");
        PURPOSE_TITLES=Collections.unmodifiableMap(titles);
    }
    private static final Map<String,List<Rule>> RULES=Map.of(
        "GENERAL",List.of(
            rule("purpose","문서 목적과 주요 내용","목적|사업명|개요|추진|안내|내용|(?:지원|사업|모집)\\s*대상|모집\\s*인원"),
            rule("action","필요한 준비와 조치","요청|회신|제출|협조|구비\\s*(?:서류|자료)|첨부|붙임|신청.?방법|접수.?방법"),
            rule("date","기한과 주요 일정","기한|마감|일정|기간|까지|일시|시행"),
            rule("cost","금액과 비용","요금|수수료|지원금|지원.?금액|위약금|[0-9][0-9,]*\\s*원")),
        "BUSINESS",List.of(
            rule("action","요청 사항과 제출 자료","요청|회신|제출|협조|구비\\s*(?:서류|자료)|첨부|붙임"),
            rule("date","기한과 주요 일정","기한|마감|일정|기간|까지|일시"),
            rule("contact","담당 부서와 문의","담당|문의|연락|전화|이메일|접수처")),
        "SUPPORT",List.of(
            rule("eligibility","지원 대상과 자격","지원.?대상|신청.?대상|사업.?대상|신청.?자격|자격.?요건"),
            rule("benefit","지원 내용과 규모","지원.?내용|지원.?금액|지원.?규모|지원금|지원율|대출.?한도"),
            rule("date","신청 기간과 일정","신청.?기간|접수.?기간|모집.?기간|마감|발표|까지"),
            rule("action","신청 방법과 제출 서류","신청.?방법|접수.?방법|제출|구비\\s*(?:서류|자료)|신청서|접수처")),
        "CONTRACT",List.of(
            rule("cost","요금과 비용","요금|수수료|보험료|청구|부가세|할인|위약금|납부|[0-9][0-9,]*\\s*원"),
            rule("change","변경 내용과 적용 시점","변경|인상|인하|기존|신설|개정|종전|적용|시행"),
            rule("termination","계약 기간·갱신·해지","계약.?기간|약정|갱신|해지|환불|철회")));
    private static final Rule EXCEPTIONS=rule("exceptions","제한·예외와 주의 사항","제외|예외|단서|다만|유의|주의|불가|취소|불이익|미납|미제출|하지\\s*않|아니");
    // Leading symbols that mark list items and sections in Korean notices.
    private static final Pattern BULLETS=re("^[\\s□■○●◦▪◆◇※*☞▶►•ㅇ#]+"),SPACE=re("\\s"),SPACES=re("\\s+"),LEAD=re("^\\s+"),TRAIL=re("\\s+$");
    // A notice often puts the label on one line and the answer on the next.
    private static final List<Map.Entry<String,Pattern>> SUPPORT_LABELS=List.of(
        Map.entry("eligibility",re("^(?:(?:지원|신청|모집|참여)\\s*(?:대상|자격|요건)|자격\\s*요건)")),
        Map.entry("benefit",re("^지원\\s*(?:내용|규모|금액|혜택)")),
        Map.entry("date",re("^(?:(?:신청|접수|모집)\\s*기간|(?:선정|추진)\\s*일정|선정\\s*발표)")),
        Map.entry("action",re("^(?:(?:신청|접수)\\s*방법|제출\\s*서류|접수처)")),
        Map.entry("exceptions",re("^(?:유의\\s*사항|주의\\s*사항|제외\\s*대상)")));
    private static final Pattern NUMBERED=re("^\\s*(?:제?\\d+[.)]|[Ⅰ-Ⅹ]+[.])\\s*"),LABEL_SEP=re("^[\\s:：-]+"),VALUED=re("[:：]\\s*\\S"),BOX=re("^[□■]");
    private static final Pattern CONTACT=re("[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|0\\d{1,2}[-)\\s]+\\d{3,4}[-\\s]+\\d{4}",Pattern.CASE_INSENSITIVE);
    // Calendar forms only: year-month(-day), month-day, a day with its weekday, a clock time or a relative period.
    private static final Pattern DATE=re("\\d{4}\\s*[./년-]\\s*\\d{1,2}(?!\\d)|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{1,2}\\s*[.일]?\\s*\\(\\s*[월화수목금토일]\\s*\\)|\\d{1,2}:\\d{2}|\\d+\\s*(?:일|개월|주|시간)\\s*(?:이내|전|후|까지|간)");
    private static final Pattern MATERIAL=re("신청서|계획서|보고서|증명서|등록증|확인서|동의서|공문|명단|사본|서식"),ACTION_VERB=re("제출|회신|신청|접수|등록|첨부|송부|작성");
    private static final Map<String,Pattern> LABELS=Map.of(
        "date",re("^[가-힣]{0,4}\\s*(?:기간|기한|마감|일정|일시)$"),
        "contact",re("^(?:문의(?:처)?|연락처|[가-힣]*담당(?:자|\\s*부서)|전화|이메일)$"),
        "action",re("^(?:제출\\s*(?:서류|자료)|구비\\s*서류|요청\\s*사항|신청\\s*방법)$"));
    private static final Pattern LEADER=re("(?:[.·ㆍ…‥．]\\s*){3,}(?:\\d{1,3})?\\s*$"),NUM_START=re("^\\d+[.)]");
    private static final Pattern COLON=re("[:：]"),ORDERED=re("^\\s*(?:[가-하][.)]|[①-⑳➀-➉]|[-–·])"),HEAD_NUM=re("^\\d+[.)]?\\s*"),DIGIT=re("\\d"),
        PREDICATE=re("(?:다|요|함|음|임|됨)[.)\\s]*$"),SECTION=re("^\\s*(?:\\d+[.)]?\\s|[□■])"),ROW_PREDICATE=re("(?:다|요)[.)\\s]*$"),CELL_NUMBER=re("^\\d");
    private static final Pattern PURPOSE_LABEL=re("^(?:사업명|사업목적|목적|(?:사업|지원|모집|신청)대상|모집인원|지원내용|주요내용)[:：]\\S"),INTENT=re("위해|위하여|목적으로"),
        APPLY=re("접수|신청|마감|제출"),UNIT=re("[가-힣]{2,}(?:팀|센터|사무국|지원단|과)(?:\\s|$|[,.)])"),
        HOW=re("하여|해야|합니다|하세요|바랍니다|까지|온라인|이메일|통해|회신해|제출해|접수해|필수"),
        CONDITION=re("경우|경우에는|자는|기업은|시에는|되면|대상에서|할 수 없|할 수 있|됩니다|않습니다|불가$"),AMOUNT=re("\\d[\\d,.]*\\s*(?:천|만|억)?\\s*(?:원|%)");
    private static final Pattern LABEL_NUM=re("^(?:\\d+[.)]\\s*)?"),LABEL_END=re("[:：\\s]+$"),NEXT_NUMBERED=re("^\\d+[.)]\\s+[가-힣]"),ID_NUMBER=Pattern.compile(".(\\d{1,9})");

    private static boolean find(Pattern p,String s){return p.matcher(s).find();}
    /** JS s.replace(re,'') without the g flag: only the first match goes. */
    private static String cut(Pattern p,String s){return p.matcher(s).replaceFirst("");}
    private static String squeeze(String s){return SPACE.matcher(s).replaceAll("");}
    /** JS String.trim. */
    static String trim(String s){return cut(TRAIL,cut(LEAD,s));}

    private static Map<String,List<Entry>> supportGroups(List<Block> blocks) {
        var groups=new HashMap<String,List<Entry>>();
        String context=null;
        for(var block:blocks) {
            String line=trim(cut(BULLETS,cut(NUMBERED,block.text())));
            var label=SUPPORT_LABELS.stream().filter(l->find(l.getValue(),line)).findFirst().orElse(null);
            String rest=label!=null?cut(LABEL_SEP,cut(label.getValue(),line)):line;
            // 'label: value' answers itself; a numbered or value-less label opens a section for the lines below it.
            boolean valued=find(VALUED,line),numbered=find(NUMBERED,block.text());
            if(label!=null)context=numbered||!valued?label.getKey():null;
            else if(numbered||find(BOX,block.text()))context=null;
            if(rest.isEmpty())continue;
            // '까지' alone also ends limits such as '최대 3세까지'; a schedule needs an actual date.
            var direct=RULES.get("SUPPORT").stream().filter(r->find(r.pattern(),line)&&(!r.id().equals("date")||find(DATE,line))).map(Rule::id).toList();
            var ids=new LinkedHashSet<String>(label!=null?List.of(label.getKey()):"eligibility".equals(context)&&!direct.contains("date")?List.of(context)
                :!direct.isEmpty()?direct:context!=null?List.of(context):List.of());
            if(find(EXCEPTIONS.pattern(),line))ids.add("exceptions");
            // Show values first and section labels last; both stay in the evidence.
            for(var id:ids)groups.computeIfAbsent(id,k->new ArrayList<>()).add(new Entry(block,valued?-1:label!=null?1:0));
        }
        return groups;
    }
    private static List<Block> contentBlocks(List<Block> blocks) {
        // OCR may repeat a dotted contents entry on the same page with damaged leaders.
        var titles=new LinkedHashSet<String>();
        for(var b:blocks)if(find(LEADER,b.text()))titles.add(b.page()+":"+squeeze(cut(LEADER,b.text())));
        // A numbered line that starts with a contents title of the same page is that entry again, whatever follows it.
        return blocks.stream().filter(b->{
            if(find(LEADER,b.text()))return false;
            String text=b.page()+":"+squeeze(b.text());
            return !find(NUM_START,trim(b.text()))||titles.stream().noneMatch(text::startsWith);
        }).toList();
    }
    // A heading carries no value: no colon, no number and no predicate. It is short, or opens a numbered or □ section.
    private static boolean heading(String text) {
        if(find(COLON,text)||find(ORDERED,text))return false;
        String body=cut(HEAD_NUM,cut(BULLETS,text));
        if(find(DIGIT,body)||find(PREDICATE,body))return false;
        return squeeze(body).length()<=10||find(SECTION,text);
    }
    // A table row: no predicate and more numeric cells than words.
    private static boolean tableRow(String text) {
        if(find(ROW_PREDICATE,text))return false;
        String[] cells=SPACES.split(trim(text));
        long numeric=Arrays.stream(cells).filter(c->find(CELL_NUMBER,c)).count();
        return numeric>=3&&numeric>cells.length-numeric;
    }
    private static int factScore(String id,String text) {
        return switch(id) {
            case "purpose" -> find(PURPOSE_LABEL,squeeze(cut(BULLETS,text)))?6:find(INTENT,text)?4:0;
            case "date" -> find(DATE,text)?5+(find(APPLY,text)?1:0):0;
            case "contact" -> find(CONTACT,text)?6:find(UNIT,text)?3:0;
            case "action" -> (find(MATERIAL,text)?3:0)+(find(ACTION_VERB,text)&&find(HOW,text)?3:0);
            case "exceptions" -> find(CONDITION,text)?4:0;
            case "cost" -> !tableRow(text)&&find(AMOUNT,text)?5:0;
            default -> 0;
        };
    }
    private static List<Fact> factCandidates(List<Block> blocks,String id,Pattern pattern) {
        var matches=new ArrayList<Fact>();
        var labelPattern=LABELS.get(id);
        for(int i=0;i<blocks.size();i++) {
            var block=blocks.get(i);
            if(labelPattern!=null&&find(labelPattern,cut(LABEL_END,cut(LABEL_NUM,cut(BULLETS,block.text()))))) {
                var next=i+1<blocks.size()?blocks.get(i+1):null;
                if(next!=null&&next.page()==block.page()&&factScore(id,next.text())>0&&!find(NEXT_NUMBERED,next.text())&&!find(pattern,next.text())) {
                    matches.add(new Fact(new Block(block.id()+"–"+next.id(),block.page(),block.text()+" · "+next.text()),factScore(id,next.text())+1));
                    i++;
                }
                continue;
            }
            if(!find(pattern,block.text())&&!(id.equals("contact")&&find(CONTACT,block.text())))continue;
            int score=factScore(id,block.text());
            if(score==0&&heading(block.text()))continue;
            matches.add(new Fact(block,score));
        }
        return matches;
    }
    /** JS parseInt(id.slice(1)): 'b3–b4' orders as 3. Ids are always 'b' and a number. */
    private static int idNumber(String id){var m=ID_NUMBER.matcher(id);return m.lookingAt()?Integer.parseInt(m.group(1)):0;}

    static List<Section> summarize(List<Block> source,String purpose) {
        if(purpose==null||!RULES.containsKey(purpose))throw new IllegalArgumentException("요약 목적을 다시 선택해 주세요.");
        var rules=new ArrayList<>(RULES.get(purpose));rules.add(EXCEPTIONS);
        var blocks=contentBlocks(source);
        var grouped=purpose.equals("SUPPORT")?supportGroups(blocks):null;
        var sections=new ArrayList<Section>();
        for(var rule:rules) {
            var entries=grouped!=null?grouped.getOrDefault(rule.id(),List.of()):List.<Entry>of();
            var facts=grouped!=null?List.<Fact>of():factCandidates(blocks,rule.id(),rule.pattern());
            List<Block> evidence=grouped!=null?entries.stream().map(Entry::block).toList():facts.stream().map(Fact::block).toList();
            // Without a purpose label, the first sentence-length lines usually state what the document is about.
            if(rule.id().equals("purpose")&&evidence.isEmpty())evidence=blocks.stream().filter(b->b.text().length()>=20).limit(3).toList();
            // Prefer lines that carry an actual value; bare headings and keyword-only matches stay in the evidence list.
            var scored=facts.stream().anyMatch(f->f.score()>0)?facts.stream().filter(f->f.score()>0).toList():facts;
            var candidates=grouped!=null?entries.stream().sorted(Comparator.comparingInt(Entry::rank)).map(Entry::block).toList()
                :!scored.isEmpty()?scored.stream().sorted(Comparator.comparingInt(Fact::score).reversed()).map(Fact::block).toList():evidence;
            // Keep source clauses intact; a cut sentence can lose its exception.
            var seen=new HashSet<String>();int length=0;
            var excerpts=new ArrayList<Block>();
            for(var b:candidates) {
                int n=b.text().length();
                if(n>260||seen.size()>=3||length+n>650||seen.contains(b.text())||heading(b.text()))continue;
                seen.add(b.text());length+=n;excerpts.add(b);
            }
            if(grouped==null)excerpts.sort(Comparator.comparingInt(Block::page).thenComparingInt(b->idNumber(b.id())));
            sections.add(new Section(rule.id(),rule.title(),evidence,excerpts));
        }
        return sections;
    }
}
