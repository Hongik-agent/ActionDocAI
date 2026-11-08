package ai.actiondoc;

import static ai.actiondoc.Models.*;
import java.util.*;
import java.util.regex.Pattern;

/** Local, source-backed organization for a user-selected use case. */
final class SummaryPurpose {
    private record Rule(String id,String title,String pattern) {}
    static String validate(String value) {
        if(value==null)return "GENERAL"; // Older clients retain their existing behavior.
        if(!Set.of("GENERAL","BUSINESS","SUPPORT","CONTRACT").contains(value))
            throw new IllegalArgumentException("요약 목적을 다시 선택해 주세요.");
        return value;
    }
    static Analysis apply(Document doc,Analysis report,String purpose) {
        if(purpose.equals("GENERAL"))return report;
        List<Rule> rules=switch(purpose) {
            case "BUSINESS" -> List.of(
                new Rule("requests","요청 사항과 제출 자료","요청|회신|제출|협조|구비|첨부|붙임"),
                new Rule("schedule","기한과 주요 일정","기한|마감|일정|기간|까지|일시"),
                new Rule("contact","담당 부서와 문의","담당|문의|연락|전화|이메일|접수처"));
            case "SUPPORT" -> List.of(
                new Rule("eligibility","지원 대상과 자격","지원.?대상|신청.?대상|사업.?대상|신청.?자격|자격.?요건"),
                new Rule("benefit","지원 내용과 규모","지원.?내용|지원.?금액|지원.?규모|지원금|지원율|대출.?한도"),
                new Rule("schedule","신청 기간과 일정","신청.?기간|접수.?기간|모집.?기간|마감|발표|까지"),
                new Rule("procedure","신청 방법과 제출 서류","신청.?방법|접수.?방법|제출|구비|신청서|접수처"));
            case "CONTRACT" -> List.of(
                new Rule("cost","요금과 비용","요금|수수료|보험료|청구|부가세|할인|위약금|납부|[0-9][0-9,]*\\s*원"),
                new Rule("change","변경 내용과 적용 시점","변경|인상|인하|기존|신설|개정|종전|적용|시행"),
                new Rule("termination","계약 기간·갱신·해지","계약.?기간|약정|갱신|해지|환불|철회"));
            default -> throw new IllegalArgumentException("요약 목적을 다시 선택해 주세요.");
        };
        List<Rule> all=new ArrayList<>(rules);
        all.add(new Rule("exceptions","제한·예외와 주의 사항","제외|예외|단서|다만|유의|주의|불가|취소|불이익|미납|미제출"));
        List<ReportSection> sections=new ArrayList<>();
        for(var rule:all) {
            Pattern pattern=Pattern.compile(rule.pattern());
            var source=doc.blocks().stream().filter(b->pattern.matcher(b.text()).find()).toList();
            String body=source.isEmpty()?"이 항목에 해당하는 원문을 찾지 못했습니다. 조건이 없다는 뜻은 아니므로 원문을 확인해 주세요.":AnalysisService.sourcePreview(source.stream().map(Block::text).toList());
            sections.add(new ReportSection("purpose-"+rule.id(),rule.title(),body,source.stream().map(b->new Evidence(b.id(),b.page(),b.text())).toList(),false));
        }
        String label=switch(purpose){case "BUSINESS"->"공문·업무 요청";case "SUPPORT"->"지원사업·모집 공고";default->"요금·계약 안내";};
        String overview=label+" 목적에 맞춰 원문을 발췌했습니다. 선택한 분류는 문서 종류나 개인별 적용 여부를 확정하지 않습니다. "+report.overview();
        return new Analysis(report.id(),report.documentId(),report.title(),report.mode(),report.decision(),report.findings(),report.questions(),report.warnings(),report.chunks(),report.createdAt(),report.documentType(),overview,List.copyOf(sections),report.periodStatus(),report.personalChecks(),report.facts(),report.stages(),report.actionRoutes());
    }
}
