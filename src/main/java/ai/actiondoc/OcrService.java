package ai.actiondoc;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
@Service
public class OcrService {
    public record Result(String text,double confidence,int lowConfidenceLines) {}
    private final String command;
    public OcrService() { this(defaultCommand()); }
    OcrService(String command) { this.command=command; }
    private static String defaultCommand() {
        String configured=System.getenv().getOrDefault("ACTIONDOC_OCR_COMMAND","");
        if(!configured.isBlank())return configured;
        for(String candidate:List.of("ActionDoc AI.app/Contents/Resources/actiondoc-ocr","desktop/build/actiondoc-ocr"))
            if(Files.isExecutable(Path.of(candidate)))return Path.of(candidate).toAbsolutePath().toString();
        return "";
    }
    public boolean available() { return !command.isBlank() && Files.isExecutable(Path.of(command)); }
    public Result recognize(BufferedImage image,int page) {
        if(!available())throw new IllegalArgumentException(page+"페이지에 OCR이 필요합니다. 자동 문자 인식 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        Path directory=null;Process process=null;
        try {
            directory=Files.createTempDirectory("actiondoc-ocr-");
            Path input=directory.resolve("page.png"),output=directory.resolve("result.json"),error=directory.resolve("error.log");
            ImageIO.write(image,"png",input.toFile());
            process=new ProcessBuilder(command,input.toString(),output.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(error.toFile()).start();
            if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new IllegalArgumentException(page+"페이지의 자동 OCR 시간이 초과되었습니다. 해당 페이지를 고화질로 다시 스캔하거나 문서를 나누어 주세요.");}
            if(process.exitValue()!=0 || !Files.exists(output) || Files.size(output)>2_000_000)throw new IllegalArgumentException(page+"페이지의 자동 OCR을 완료하지 못했습니다. 원본 이미지의 방향과 해상도를 확인해 주세요.");
            JsonNode result=new ObjectMapper().readTree(output.toFile());
            if(!result.path("text").isTextual() || !result.path("confidence").isNumber())throw new IOException("Invalid OCR response");
            String text=result.path("text").asText();double confidence=result.path("confidence").asDouble();
            if(text.isBlank() || !Double.isFinite(confidence) || confidence<0.35)throw new IllegalArgumentException(page+"페이지를 자동 OCR로 처리했지만 읽을 수 있는 텍스트를 확보하지 못했습니다. 원본의 방향·해상도를 확인해 주세요. 이 페이지를 제외한 분석은 확정하지 않았습니다.");
            return new Result(text,confidence,result.path("lowConfidenceLines").asInt(0));
        } catch(IllegalArgumentException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("OCR이 중단되었습니다. 다시 시도해 주세요.");}
        catch(Exception e){throw new IllegalArgumentException(page+"페이지 OCR 처리 중 오류가 발생했습니다. 앱을 다시 시작해 주세요.");}
        finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            if(directory!=null)try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException ignored){}
        }
    }
}
