package ai.actiondoc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;

/** PDFBox stays on the caller thread; only detached page images enter the OCR workers. */
final class PdfPagePipeline {
    record Page(int number,String text,List<String> links,OcrService.Result ocr,boolean blank) {}
    static final int MAX_IN_FLIGHT=2;

    static void read(PDDocument pdf,OcrService ocr,Consumer<Page> consume,Consumer<Models.DocumentProgress> progress) throws IOException {
        var workers=Executors.newFixedThreadPool(MAX_IN_FLIGHT,r->{var thread=new Thread(r,"actiondoc-pdf-ocr");thread.setDaemon(true);return thread;});
        var pending=new ArrayDeque<Future<Page>>();
        var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
        var renderer=new PDFRenderer(pdf);renderer.setSubsamplingAllowed(true);
        int pages=pdf.getNumberOfPages(),completed=0;
        long started=System.nanoTime();
        progress.accept(state(0,pages));
        try {
            for(int number=1;number<=pages;number++) {
                if(pending.size()==MAX_IN_FLIGHT) {
                    consume.accept(await(pending.removeFirst(),started));
                    progress.accept(state(++completed,pages));
                }
                if(System.nanoTime()-started>Duration.ofMinutes(8).toNanos())throw timeout();
                stripper.setStartPage(number);stripper.setEndPage(number);
                String text=stripper.getText(pdf);
                PDPage page=pdf.getPage(number-1);
                List<String> links=links(page);
                boolean needsOcr=text.isBlank() || hasLargeImage(page.getResources(),0);
                if(!needsOcr) {
                    pending.add(CompletableFuture.completedFuture(new Page(number,text,links,null,false)));
                    continue;
                }
                var bounds=page.getCropBox();
                double width=Math.max(1,bounds.getWidth()),height=Math.max(1,bounds.getHeight());
                float scale=(float)Math.min(200.0/72.0,Math.min(4096.0/Math.max(width,height),Math.sqrt(10_000_000.0/(width*height))));
                BufferedImage image=renderer.renderImage(number-1,scale,ImageType.GRAY);
                if(text.isBlank() && isBlank(image)) {
                    image.flush();
                    pending.add(CompletableFuture.completedFuture(new Page(number,text,links,null,true)));
                } else {
                    int originalPage=number;
                    pending.add(workers.submit(()->{
                        try{return new Page(originalPage,text,links,ocr.recognize(image,originalPage),false);}
                        finally{image.flush();}
                    }));
                }
            }
            while(!pending.isEmpty()) {
                consume.accept(await(pending.removeFirst(),started));
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
    private static Page await(Future<Page> task,long started) throws IOException {
        try {
            long remaining=Duration.ofMinutes(8).toNanos()-(System.nanoTime()-started);
            if(remaining<=0)throw timeout();
            return task.get(remaining,TimeUnit.NANOSECONDS);
        } catch(InterruptedException e){task.cancel(true);Thread.currentThread().interrupt();throw new IllegalArgumentException("PDF 처리가 중단되었습니다. 다시 시도해 주세요.");}
        catch(TimeoutException e){task.cancel(true);throw timeout();}
        catch(ExecutionException e){
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
    private static boolean hasLargeImage(PDResources resources,int depth) throws IOException {
        if(resources==null || depth>8)return false;
        for(var name:resources.getXObjectNames()) {
            var object=resources.getXObject(name);
            if(object instanceof PDImageXObject image && (long)image.getWidth()*image.getHeight()>=250000)return true;
            if(object instanceof PDFormXObject form && hasLargeImage(form.getResources(),depth+1))return true;
        }
        return false;
    }
    private static boolean isBlank(BufferedImage image) {
        long dark=0,limit=Math.max(10,(long)image.getWidth()*image.getHeight()/100000);
        var raster=image.getRaster();
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if(raster.getSample(x,y,0)<245 && ++dark>limit)return false;
        return true;
    }
}
