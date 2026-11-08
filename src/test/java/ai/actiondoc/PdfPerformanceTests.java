package ai.actiondoc;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;

class PdfPerformanceTests {
    @Test void optionalRealPdfTiming() throws Exception {
        String path=System.getProperty("actiondoc.pdfBenchmark");
        Assumptions.assumeTrue(path!=null);
        var ocr=new OcrService(){
            @Override public Result recognize(java.awt.image.BufferedImage image,int page){
                long start=System.nanoTime();
                var result=super.recognize(image,page);
                System.out.printf("OCR page=%d ms=%.0f%n",page,(System.nanoTime()-start)/1e6);
                return result;
            }
        };
        var source=Path.of(path);
        var file=new MockMultipartFile("file",source.getFileName().toString(),"application/pdf",Files.readAllBytes(source));
        long start=System.nanoTime();
        var doc=new DocumentService(ocr).normalize(file,null);
        System.out.printf("PDF_BENCHMARK seconds=%.3f pages=%d ocrPages=%d characters=%d%n",(System.nanoTime()-start)/1e9,doc.pages(),doc.ocrPages().size(),doc.characters());
        String output=System.getProperty("actiondoc.pdfBenchmarkOutput");
        if(output!=null)Files.writeString(Path.of(output),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(doc));
        Assertions.assertFalse(doc.blocks().isEmpty());
    }
}
