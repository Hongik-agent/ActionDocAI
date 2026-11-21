package ai.actiondoc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.*;

/** PDFBox stays on the caller thread; only detached page images enter the OCR workers. */
final class PdfPagePipeline {
    record Page(int number,String text,List<String> links,OcrService.Result ocr,boolean blank) {}
    static final int MAX_IN_FLIGHT=2;
    static final String CANCELLED="문서 읽기를 취소했습니다.";

    static void read(PDDocument pdf,OcrService ocr,Consumer<Page> consume,Consumer<Models.DocumentProgress> progress,BooleanSupplier cancelled) throws IOException {
        var workers=Executors.newFixedThreadPool(MAX_IN_FLIGHT,r->{var thread=new Thread(r,"actiondoc-pdf-ocr");thread.setDaemon(true);return thread;});
        var pending=new ArrayDeque<Future<Page>>();
        var renderer=new PDFRenderer(pdf);renderer.setSubsamplingAllowed(true);
        int pages=pdf.getNumberOfPages(),completed=0;
        long started=System.nanoTime();
        progress.accept(state(0,pages));
        try {
            for(int number=1;number<=pages;number++) {
                if(pending.size()==MAX_IN_FLIGHT) {
                    consume.accept(await(pending.removeFirst(),started,cancelled));
                    progress.accept(state(++completed,pages));
                }
                if(cancelled.getAsBoolean())throw new IllegalArgumentException(CANCELLED);
                if(System.nanoTime()-started>Duration.ofMinutes(8).toNanos())throw timeout();
                String text=PdfLines.join(LayoutTextStripper.lines(pdf,number));
                PDPage page=pdf.getPage(number-1);
                List<String> links=links(page);
                var bounds=page.getCropBox();
                double width=Math.max(1,bounds.getWidth()),height=Math.max(1,bounds.getHeight());
                float scale=(float)Math.min(200.0/72.0,Math.min(4096.0/Math.max(width,height),Math.sqrt(10_000_000.0/(width*height))));
                // Under 16 rendered pixels is a spacer, bullet or rule, too small to hold readable text.
                var images=ImageRegions.find(page).stream().filter(region->region.width()*scale>=16 && region.height()*scale>=16).toList();
                // Under 40 characters is less than a sentence, so the page's content must be in its image layer.
                boolean scanned=text.isBlank() || text.replaceAll("\\s","").length()<40 && !images.isEmpty();
                // Images under 250,000 pixels (about 500×500) are logos and icons rather than text-bearing figures.
                var figures=scanned ? List.<ImageRegions.Region>of() : images.stream().filter(region->region.pixels()>=250_000).toList();
                if(!scanned && figures.isEmpty()) {
                    pending.add(CompletableFuture.completedFuture(new Page(number,text,links,null,false)));
                    continue;
                }
                BufferedImage image=renderer.renderImage(number-1,scale,ImageType.GRAY);
                if(text.isBlank() && isBlank(image)) {
                    image.flush();
                    pending.add(CompletableFuture.completedFuture(new Page(number,text,links,null,true)));
                } else {
                    int originalPage=number;
                    var targets=new ArrayList<BufferedImage>(scanned ? List.of(image) : List.of());
                    for(var figure:figures)targets.add(ImageRegions.crop(image,page,figure,scale));
                    pending.add(workers.submit(()->{
                        try{
                            OcrService.Result merged=null;
                            for(var target:targets) {
                                var found=ocr.recognize(target,originalPage);
                                // Only a page without native text depends on OCR alone; elsewhere an unreadable result just adds nothing.
                                if(text.isBlank())OcrService.requireReadable(found,originalPage);
                                if(OcrService.readable(found))merged=merged==null ? found:new OcrService.Result(merged.text()+"\n"+found.text(),Math.min(merged.confidence(),found.confidence()),merged.lowConfidenceLines()+found.lowConfidenceLines());
                            }
                            return new Page(originalPage,text,links,merged,false);
                        }
                        finally{image.flush();}
                    }));
                }
            }
            while(!pending.isEmpty()) {
                consume.accept(await(pending.removeFirst(),started,cancelled));
                progress.accept(state(++completed,pages));
            }
        } finally {
            for(var task:pending)task.cancel(true);
            workers.shutdownNow();
            try{workers.awaitTermination(6,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
    }

    private static Models.DocumentProgress state(int completed,int total) {
        return new Models.DocumentProgress("READING",completed,total,"PDF "+completed+" / "+total+"쪽 읽음 · 이미지 페이지는 자동 OCR 처리 중");
    }
    private static Page await(Future<Page> task,long started,BooleanSupplier cancelled) throws IOException {
        try {
            // Waits in one-second slices so a cancel request stops the read within about a second.
            while(true) {
                if(cancelled.getAsBoolean()){task.cancel(true);throw new IllegalArgumentException(CANCELLED);}
                long remaining=Duration.ofMinutes(8).toNanos()-(System.nanoTime()-started);
                if(remaining<=0){task.cancel(true);throw timeout();}
                try{return task.get(Math.min(remaining,TimeUnit.SECONDS.toNanos(1)),TimeUnit.NANOSECONDS);}catch(TimeoutException e){}
            }
        } catch(InterruptedException e){task.cancel(true);Thread.currentThread().interrupt();throw new IllegalArgumentException("PDF 처리가 중단되었습니다. 다시 시도해 주세요.");}
        catch(ExecutionException e){
            if(cancelled.getAsBoolean())throw new IllegalArgumentException(CANCELLED);
            if(e.getCause() instanceof IllegalArgumentException invalid)throw invalid;
            if(e.getCause() instanceof IOException io)throw io;
            throw new IllegalArgumentException("PDF OCR을 완료하지 못했습니다. 다시 시도해 주세요.");
        }
    }
    private static IllegalArgumentException timeout(){return new IllegalArgumentException("문서 OCR 처리 시간이 8분을 초과했습니다. 문서를 나누어 입력해 주세요.");}
    private static List<String> links(PDPage page) throws IOException {
        var result=new ArrayList<String>();
        for(var annotation:page.getAnnotations()) {
            if(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI action) {
                String address=action.getURI();
                if(address!=null && address.length()<=2048 && address.matches("(?i)^(https?://|mailto:|tel:).*") && address.chars().noneMatch(Character::isISOControl))result.add(address);
            }
        }
        return List.copyOf(result);
    }
    private static boolean isBlank(BufferedImage image) {
        long dark=0,limit=Math.max(10,(long)image.getWidth()*image.getHeight()/100000);
        var raster=image.getRaster();
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(raster.getSample(x,y,0)<245 && ++dark>limit)return false;
        return true;
    }
}
