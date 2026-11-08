package ai.actiondoc;
import static ai.actiondoc.Models.*;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
@Service
public class DocumentService {
    private final OcrService ocr;
    public DocumentService() { this(new OcrService()); }
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentService(OcrService ocr) { this.ocr=ocr; }
    public Document normalize(MultipartFile file, String text) throws IOException { return normalize(file,text,p->{}); }
    public Document normalize(MultipartFile file, String text, java.util.function.Consumer<DocumentProgress> progress) throws IOException {
        boolean uploaded = file != null && !file.isEmpty();
        if (uploaded && text != null && !text.isBlank()) throw new IllegalArgumentException("파일 또는 텍스트 중 한 가지만 입력해 주세요.");
        List<Block> blocks = new ArrayList<>(); List<String> warnings = new ArrayList<>(); List<Integer> ocrPages=new ArrayList<>();
        String name = "직접 입력한 안내문", type = "TEXT"; int pages = 1;
        if (uploaded) {
            if (file.getSize() > DocumentLimits.MAX_FILE_BYTES) throw new IllegalArgumentException(DocumentLimits.FILE_SIZE_MESSAGE);
            name = Objects.toString(file.getOriginalFilename(), "document").replaceAll("[\\\\/\\r\\n]", "_");
            byte[] data = file.getBytes(); String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                type = "PDF";
                if (data.length < 5 || !new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("올바른 PDF 파일이 아닙니다.");
                try (var pdf = Loader.loadPDF(data)) {
                    if (pdf.isEncrypted()) throw new IllegalArgumentException("암호화 PDF는 지원하지 않습니다. 암호를 제거한 파일을 올려 주세요.");
                    pages = pdf.getNumberOfPages();
                    if (pages > DocumentLimits.MAX_PDF_PAGES) throw new IllegalArgumentException("이 PDF는 " + pages + "쪽입니다. 시연용 요약은 " + DocumentLimits.MAX_PDF_PAGES + "쪽까지 지원합니다. 필요한 부분을 별도 PDF로 저장해 다시 넣어 주세요.");
                    List<Integer> blankPages=new ArrayList<>(),uncertainPages=new ArrayList<>();
                    PdfPagePipeline.read(pdf,ocr,page->{
                        if(page.blank())blankPages.add(page.number());
                        if(page.ocr()!=null) {
                            ocrPages.add(page.number());
                            if(page.ocr().confidence()<0.8 || page.ocr().lowConfidenceLines()>0)uncertainPages.add(page.number());
                            if(!page.text().isBlank()) {
                                addBlocks(blocks,page.text(),page.number());
                                String nativeCompact=page.text().replaceAll("\\s+","");
                                for(String line:page.ocr().text().split("\\R"))
                                    if(!line.isBlank() && !nativeCompact.contains(line.replaceAll("\\s+","")))addBlocks(blocks,line,page.number());
                            }else addBlocks(blocks,page.ocr().text(),page.number());
                        }else if(!page.blank())addBlocks(blocks,page.text(),page.number());
                        for(String address:page.links())addBlocks(blocks,"PDF 연결 주소: "+address,page.number());
                        if(blocks.stream().mapToInt(b->b.text().length()).sum()>160000)throw new IllegalArgumentException("본문은 160,000자 이하여야 합니다. 문서를 나누어 입력해 주세요.");
                    },progress);
                    if(!ocrPages.isEmpty())warnings.add("자동 OCR 적용 페이지: "+ocrPages+". Mac의 한국어·영어 텍스트 인식을 사용했습니다. 인용문은 OCR 결과와의 일치이며 원본 이미지의 정확성을 보장하지 않습니다. 금액·날짜·표·각주는 원본과 대조해 주세요.");
                    if(!uncertainPages.isEmpty())warnings.add("OCR 인식 신뢰도가 낮은 문장이 있는 페이지: "+uncertainPages+". 해당 페이지의 숫자와 조건을 원본에서 확인해 주세요.");
                    if(!blankPages.isEmpty())warnings.add("본문이 없는 빈 페이지를 건너뛰었습니다: "+blankPages+". 페이지 번호는 원본 기준으로 유지했습니다.");
                    warnings.add("PDF는 텍스트와 페이지 위치를 보존해 변환했습니다. 복잡한 표의 열 순서와 금액·각주는 원본도 확인해 주세요.");
                } catch (IOException e) { throw new IllegalArgumentException("PDF를 읽지 못했습니다. 손상·암호화 여부를 확인해 주세요."); }
            } else if (lower.endsWith(".hwp")) {
                type = "HWP";
                // Zero denotes unavailable physical page positions, including in saved tasks.
                pages = 0;
                addBlocks(blocks, HwpTextExtractor.extract(data), 0);
                warnings.add(HwpTextExtractor.WARNING);
            } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                type = "HTML";
                var html = Jsoup.parse(new java.io.ByteArrayInputStream(data), null, "");
                html.select("script,style,noscript,iframe,svg,template").remove();
                html.select("br").append("\\n");
                html.select("p,div,section,article,h1,h2,h3,li,tr").prepend("\\n");
                addBlocks(blocks, html.body().wholeText().replace("\\n", "\n"), 1);
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                type = "TEXT"; addBlocks(blocks, new String(data, StandardCharsets.UTF_8), 1);
            } else throw new IllegalArgumentException("PDF, HWP, HTML, TXT, MD 파일을 지원합니다.");
        } else addBlocks(blocks, Objects.toString(text, ""), 1);
        if (blocks.isEmpty()) throw new IllegalArgumentException("분석할 본문이 없습니다.");
        int characters = blocks.stream().mapToInt(b -> b.text().length()).sum();
        if (characters > 160000) throw new IllegalArgumentException("본문은 160,000자 이하여야 합니다. 문서를 나누어 입력해 주세요.");
        StringBuilder md = new StringBuilder(); int currentPage = -1;
        for (Block block : blocks) {
            if (block.page() != currentPage) { currentPage = block.page(); md.append("\n## ").append(currentPage > 0 ? currentPage + "페이지" : "본문").append("\n\n"); }
            md.append("<!-- ").append(block.id()).append(" -->\n").append(block.text()).append("\n\n");
        }
        return new Document(UUID.randomUUID().toString(), name, type, pages, characters, md.toString().strip(), List.copyOf(blocks), warnings, List.copyOf(ocrPages));
    }
    private void addBlocks(List<Block> blocks, String text, int page) {
        text = text.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n');
        for (String line : text.split("\n")) {
            String clean = line.replaceAll("[\\t\\x0B\\f ]+", " ").strip();
            if (clean.isBlank()) continue;
            // Do not delete repeated lines: a repeated clause can have different scope on another page.
            for (int start = 0; start < clean.length(); start += 1800) {
                blocks.add(new Block("b" + (blocks.size() + 1), page, clean.substring(start, Math.min(start + 1800, clean.length()))));
                if (blocks.size() > 6000) throw new IllegalArgumentException("문단 수가 너무 많습니다. 문서를 나누어 입력해 주세요.");
            }
        }
    }
}
