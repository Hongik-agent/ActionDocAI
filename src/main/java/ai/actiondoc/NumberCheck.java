package ai.actiondoc;
import static ai.actiondoc.Models.*;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Value-based number comparison, ported from web/server/verify.js numbers(); shared fixtures keep both in step. */
final class NumberCheck {
    record Token(String key,String unit) {}
    private static final Pattern NUMBER=Pattern.compile("\\d{1,3}(?:,\\d{3})+(?!\\d)|\\d+(?:\\.\\d+)*"),PLACE=Pattern.compile("(?U)^\\s*([십백천])?\\s*([만억조])?"),
        SIGN=Pattern.compile("(?:^|[^\\p{L}\\p{N}])[-−]\\z"),CURRENCY=Pattern.compile("(?U)(\\p{Sc})\\s*\\z"),UNIT=Pattern.compile("(?U)^\\s*([\\p{L}%])");
    private static final Map<String,Long> SMALL=Map.of("십",10L,"백",100L,"천",1000L),LARGE=Map.of("만",10_000L,"억",100_000_000L,"조",1_000_000_000_000L);
    private NumberCheck(){}
    /** Every number as key (sign, currency, value) and unit (the letter or % right after it). */
    static List<Token> tokens(String text){
        String s=Normalizer.normalize(text,Normalizer.Form.NFKC);List<Token> out=new ArrayList<>();Matcher m=NUMBER.matcher(s);BigDecimal lastValue=null;long lastLarge=0;int lastEnd=0;
        while(m.find()){
            String[] parts=m.group().split("\\.");
            // Two or more dots mark a date or version such as 2026.5.11, never a decimal.
            List<BigDecimal> values=new ArrayList<>();if(parts.length>2)for(var p:parts)values.add(new BigDecimal(p));else values.add(new BigDecimal(m.group().replace(",","")));
            Matcher place=PLACE.matcher(s.substring(m.end()));place.find();
            boolean scaled=place.group(1)!=null||place.group(2)!=null;long large=place.group(2)==null?1:LARGE.get(place.group(2));
            BigDecimal value=values.get(values.size()-1).multiply(BigDecimal.valueOf(place.group(1)==null?1:SMALL.get(place.group(1)))).multiply(BigDecimal.valueOf(large));
            int end=m.end()+(scaled?place.end():0);String before=s.substring(0,m.start());
            // '5천만' after '1억' continues the same amount when only spaces separate them.
            if(values.size()==1&&lastValue!=null&&lastLarge>large&&before.substring(lastEnd).strip().isEmpty()){value=value.add(lastValue);out.remove(out.size()-1);}
            else for(var v:values.subList(0,values.size()-1))out.add(new Token(plain(v),""));
            String sign=SIGN.matcher(before).find()?"-":"";Matcher c=CURRENCY.matcher(before),u=UNIT.matcher(s.substring(end));
            out.add(new Token(sign+(c.find()?c.group(1):"")+plain(value.setScale(6,RoundingMode.HALF_UP)),u.find()?u.group(1):""));
            if(scaled){lastValue=value;lastLarge=large;lastEnd=end;}else lastValue=null;
        }
        return out;
    }
    private static String plain(BigDecimal v){v=v.stripTrailingZeros();return (v.signum()==0?BigDecimal.ZERO:v).toPlainString();}
    /** Statement numbers that no source number matches by value, with a unit that is empty on either side or equal. */
    static List<Token> missing(String statement,String sourceText){var source=tokens(sourceText);return tokens(statement).stream().filter(n->source.stream().noneMatch(c->c.key().equals(n.key())&&(n.unit().isEmpty()||c.unit().isEmpty()||c.unit().equals(n.unit())))).toList();}
    static boolean supported(String statement,List<Evidence> evidence){return missing(statement,String.join("\n",evidence.stream().map(Evidence::quote).toList())).isEmpty();}
}
