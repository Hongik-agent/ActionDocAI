package ai.actiondoc;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
class OcrDocumentTests {
    @Test void twoOcrPagesOverlapButEvidenceAndProgressStayInPageOrder()throws Exception{
        var active=new java.util.concurrent.atomic.AtomicInteger();
        var maximum=new java.util.concurrent.atomic.AtomicInteger();
        var started=new java.util.concurrent.CountDownLatch(2);
        OcrService parallel=new OcrService(""){@Override public Result recognize(BufferedImage image,int page){
            maximum.accumulateAndGet(active.incrementAndGet(),Math::max);started.countDown();
            try{
                assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS),"OCR pages were serialized");
                if(page==1)Thread.sleep(100);
                return new Result("원본 "+page+"쪽 신청서를 제출해 주세요.",.95,0);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("interrupted");}
            finally{active.decrementAndGet();}
        }};
        try(var pdf=new PDDocument()){
            scanPage(pdf,false);scanPage(pdf,false);
            var progress=new java.util.ArrayList<Models.DocumentProgress>();
            var doc=new DocumentService(parallel).normalize(file(pdf),null,progress::add);
            assertEquals(2,maximum.get());assertEquals(0,active.get());
            assertEquals(java.util.List.of(1,2),doc.ocrPages());
            assertEquals(java.util.List.of(1,2),doc.blocks().stream().map(Models.Block::page).toList());
            assertEquals(java.util.List.of(0,1,2),progress.stream().map(Models.DocumentProgress::completedPages).toList());
            assertTrue(progress.stream().allMatch(p->p.totalPages()==2));
        }
    }
    @Test void failedPageCancelsTheOtherOcrWorkerInsteadOfReturningPartialContent()throws Exception{
        var started=new java.util.concurrent.CountDownLatch(2);
        var cancelled=new java.util.concurrent.CountDownLatch(1);
        OcrService failing=new OcrService(""){@Override public Result recognize(BufferedImage image,int page){
            started.countDown();
            try{
                if(!started.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalArgumentException("worker did not start");
                if(page==1)throw new IllegalArgumentException("1쪽 OCR 실패");
                Thread.sleep(30_000);throw new AssertionError("OCR worker was not cancelled");
            }catch(InterruptedException e){cancelled.countDown();Thread.currentThread().interrupt();throw new IllegalArgumentException("cancelled");}
        }};
        try(var pdf=new PDDocument()){
            scanPage(pdf,false);scanPage(pdf,false);
            var error=assertThrows(IllegalArgumentException.class,()->new DocumentService(failing).normalize(file(pdf),null));
            assertEquals("1쪽 OCR 실패",error.getMessage());
            assertTrue(cancelled.await(2,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    private BufferedImage scan(){
        var image=new BufferedImage(900,1300,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,900,1300);g.setColor(Color.BLACK);g.setFont(new Font("SansSerif",Font.PLAIN,30));g.drawString("Monthly fee 36,300 KRW",60,150);g.dispose();return image;
    }
    private void textPage(PDDocument pdf,String text)throws Exception{
        var page=new PDPage();pdf.addPage(page);try(var c=new PDPageContentStream(pdf,page)){c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,730);c.showText(text);c.endText();}
    }
    private void scanPage(PDDocument pdf,boolean header)throws Exception{
        var page=new PDPage();pdf.addPage(page);try(var c=new PDPageContentStream(pdf,page)){
            c.drawImage(LosslessFactory.createFromImage(pdf,scan()),0,0,600,750);
            if(header){c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,770);c.showText("Original header");c.endText();}
        }
    }
    private MockMultipartFile file(PDDocument pdf)throws Exception{var bytes=new ByteArrayOutputStream();pdf.save(bytes);return new MockMultipartFile("file","mixed.pdf","application/pdf",bytes.toByteArray());}
    private OcrService fake(double confidence){return new OcrService(""){@Override public Result recognize(BufferedImage image,int page){assertTrue(image.getWidth()<=4096 && image.getHeight()<=4096);return new Result("월 이용요금 36,300원\n2026년 11월 25일까지 신청서를 제출해 주세요.",confidence,confidence<0.8 ? 1:0);}};}
    @Test void thirtyThirdScannedPageIsReadAndKeepsOriginalPageNumber()throws Exception{
        try(var pdf=new PDDocument()){
            for(int n=1;n<=32;n++)textPage(pdf,"Page "+n);scanPage(pdf,false);
            var doc=new DocumentService(fake(.95)).normalize(file(pdf),null);
            assertEquals(33,doc.pages());assertEquals(java.util.List.of(33),doc.ocrPages());assertTrue(doc.blocks().stream().anyMatch(b->b.page()==33 && b.text().contains("36,300원")));
            assertTrue(doc.warnings().stream().anyMatch(w->w.contains("자동 OCR 적용 페이지: [33]")));
        }
    }
    @Test void blankPagesAreReportedWithoutRenumbering()throws Exception{
        try(var pdf=new PDDocument()){
            textPage(pdf,"Page 1");pdf.addPage(new PDPage());textPage(pdf,"Page 3");
            var doc=new DocumentService(fake(.95)).normalize(file(pdf),null);
            assertEquals(3,doc.pages());assertTrue(doc.blocks().stream().anyMatch(b->b.page()==3));assertTrue(doc.blocks().stream().noneMatch(b->b.page()==2));
            assertTrue(doc.warnings().stream().anyMatch(w->w.contains("빈 페이지")&&w.contains("[2]")));
        }
    }
    @Test void mixedImageAndNativeTextKeepsBoth()throws Exception{
        try(var pdf=new PDDocument()){
            scanPage(pdf,true);var doc=new DocumentService(fake(.95)).normalize(file(pdf),null);
            assertTrue(doc.markdown().contains("Original header"));assertTrue(doc.markdown().contains("36,300원"));
        }
    }
    @Test void uncertainOcrIsClearlyReported()throws Exception{
        try(var pdf=new PDDocument()){
            scanPage(pdf,false);var doc=new DocumentService(fake(.6)).normalize(file(pdf),null);
            assertTrue(doc.warnings().stream().anyMatch(w->w.contains("OCR 인식 신뢰도")&&w.contains("[1]")));
        }
    }
    @Test void missingEngineDoesNotSilentlyDropScannedPage()throws Exception{
        try(var pdf=new PDDocument()){
            textPage(pdf,"Page 1");scanPage(pdf,false);
            var error=assertThrows(IllegalArgumentException.class,()->new DocumentService(new OcrService("")).normalize(file(pdf),null));
            assertTrue(error.getMessage().contains("2페이지"));assertTrue(error.getMessage().contains("OCR"));
        }
    }
    @Test void failedOcrDoesNotReturnPartialDocument()throws Exception{
        try(var pdf=new PDDocument()){
            textPage(pdf,"Page 1");scanPage(pdf,false);
            OcrService failing=new OcrService(""){@Override public Result recognize(BufferedImage image,int page){throw new IllegalArgumentException("OCR 실패");}};
            assertThrows(IllegalArgumentException.class,()->new DocumentService(failing).normalize(file(pdf),null));
        }
    }
}
