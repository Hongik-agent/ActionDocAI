package ai.actiondoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.*;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.*;
import kr.dogfoot.hwplib.tool.blankfilemaker.*;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class HwpDocumentTests {
    static final String ACTION = "2026년 11월 25일까지 신청서를 제출해 주세요.";
    private final DocumentService documents = new DocumentService();

    static HWPFile notice(boolean compressed) throws Exception {
        var hwp = BlankFileMaker.make();
        hwp.getFileHeader().setCompressed(compressed);
        hwp.getBodyText().getSectionList().get(0).getParagraph(0).getText().addString(ACTION);
        return hwp;
    }

    static byte[] bytes(HWPFile hwp) throws Exception {
        var out = new ByteArrayOutputStream();
        HWPWriter.toStream(hwp, out);
        return out.toByteArray();
    }

    static MockMultipartFile upload(byte[] data) {
        return new MockMultipartFile("file", "공문.HWP", "application/x-hwp", data);
    }

    @Test void compressedAndUncompressedHwpKeepKoreanTextAndEvidence() throws Exception {
        for (boolean compressed : new boolean[]{true, false}) {
            var hwp = notice(compressed);
            var next = hwp.getBodyText().addNewSection();
            EmptyParagraphAdder.add(next);
            next.getParagraph(0).getText().addString("문의: https://www.example.org/notice");
            var doc = documents.normalize(upload(bytes(hwp)), null);
            assertEquals("HWP", doc.type());
            assertEquals(0, doc.pages());
            assertTrue(doc.blocks().stream().allMatch(b -> b.page() == 0));
            assertTrue(doc.markdown().contains(ACTION));
            assertTrue(doc.markdown().contains("https://www.example.org/notice"));
            assertTrue(doc.markdown().startsWith("## 본문"));
            assertFalse(doc.markdown().contains("0페이지"));
            assertTrue(doc.ocrPages().isEmpty());
            var report = new AnalysisService(new ObjectMapper()).analyze(doc, "", "local", false);
            var action = report.findings().stream().filter(f -> f.category().equals("action")).findFirst().orElseThrow();
            assertEquals("2026-11-25", action.date());
            assertTrue(doc.blocks().stream().anyMatch(b -> b.id().equals(action.blockId()) && b.text().contains(action.quote())));
            assertTrue(report.actionRoutes().stream().anyMatch(r -> r.url().equals("https://www.example.org/notice")));
        }
    }

    @Test void textInsideTablesAndTextBoxesIsIncluded() throws Exception {
        var hwp = notice(true);
        var paragraph = hwp.getBodyText().getSectionList().get(0).getParagraph(0);
        paragraph.getText().addExtendCharForTable();
        var table = (ControlTable) paragraph.addNewControl(ControlType.Table);
        table.getTable().setRowCount(1);
        table.getTable().setColumnCount(2);
        table.getTable().addCellCountOfRow(2);
        var row = table.addNewRow();
        for (int i = 0; i < 2; i++) {
            var cell = row.addNewCell();
            cell.getListHeader().setColIndex(i);
            cell.getListHeader().setColSpan(1);
            cell.getListHeader().setRowSpan(1);
            EmptyParagraphAdder.add(cell.getParagraphList());
            var p = cell.getParagraphList().getParagraph(0);
            p.createText();
            p.getText().addString(i == 0 ? "지원 금액" : "월 33,000원");
        }
        paragraph.getText().addExtendCharForGSO();
        var box = (ControlRectangle) paragraph.addNewGsoControl(GsoControlType.Rectangle);
        box.createTextBox();
        EmptyParagraphAdder.add(box.getTextBox().getParagraphList());
        var boxed = box.getTextBox().getParagraphList().getParagraph(0);
        boxed.createText();
        boxed.getText().addString("글상자 안의 제출 서류: 주민등록등본");
        var doc = documents.normalize(upload(bytes(hwp)), null);
        for (String expected : new String[]{ACTION, "지원 금액", "월 33,000원", "주민등록등본"})
            assertTrue(doc.markdown().contains(expected), expected);
    }

    @Test void manualLineBreaksKeepSeparateSourceClauses() throws Exception {
        var hwp = notice(true);
        var text = hwp.getBodyText().getSectionList().get(0).getParagraph(0).getText();
        text.addNewCharControlChar().setCode((short)10);
        text.addString("문의: https://www.example.org/");
        var doc = documents.normalize(upload(bytes(hwp)), null);
        assertTrue(doc.blocks().stream().anyMatch(b -> b.text().equals(ACTION)));
        assertTrue(doc.blocks().stream().anyMatch(b -> b.text().equals("문의: https://www.example.org/")));
    }

    @Test void hwpLargerThanTheOldTenMbLimitIsAccepted() throws Exception {
        byte[] large = java.util.Arrays.copyOf(bytes(notice(true)), 11 * 1024 * 1024);
        assertTrue(documents.normalize(upload(large), null).markdown().contains(ACTION));
    }

    @Test void uploadAcceptsExactlyFiftyMbAndRejectsOneByteOverBeforeReading() throws Exception {
        var atLimit = new MockMultipartFile("file", "notice.txt", "text/plain", ACTION.getBytes(StandardCharsets.UTF_8)) {
            @Override public long getSize() { return 50L * 1024 * 1024; }
        };
        assertTrue(documents.normalize(atLimit, null).markdown().contains(ACTION));
        var tooLarge = new MockMultipartFile("file", "notice.txt", "text/plain", new byte[]{1}) {
            @Override public long getSize() { return 50L * 1024 * 1024 + 1; }
            @Override public byte[] getBytes() { throw new AssertionError("Oversized file was read"); }
        };
        assertTrue(assertThrows(IllegalArgumentException.class, () -> documents.normalize(tooLarge, null)).getMessage().contains("50MB"));
    }

    @Test void encryptedDistributionAndDrmFilesGiveActionableErrors() throws Exception {
        byte[] original = bytes(notice(true));
        for (var entry : java.util.Map.of(2, "암호", 4, "배포용", 16, "DRM", 256, "인증서", 1024, "DRM").entrySet()) {
            byte[] changed = replaceStream(original, "FileHeader", header(original, entry.getKey()));
            var error = assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(changed), null));
            assertTrue(error.getMessage().contains(entry.getValue()), error.getMessage());
        }
    }

    @Test void emptyInvalidRenamedAndDamagedFilesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload("plain text".getBytes(StandardCharsets.UTF_8)), null));
        assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(bytes(BlankFileMaker.make())), null));
        var ole = new POIFSFileSystem();
        ole.createDocument(new ByteArrayInputStream(new byte[256]), "FileHeader");
        var out = new ByteArrayOutputStream(); ole.writeFilesystem(out);
        assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(out.toByteArray()), null));
        byte[] damaged = replaceStream(bytes(notice(true)), "DocInfo", new byte[]{0x07, 1, 2});
        assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(damaged), null));
    }

    @Test void excessiveTextAndCompressionAreRejected() throws Exception {
        var hwp = notice(true);
        var section = hwp.getBodyText().getSectionList().get(0);
        for (int i = 0; i < 81; i++) {
            var paragraph = section.addNewParagraph();
            paragraph.createText(); paragraph.getText().addString("가".repeat(2000));
            paragraph.createCharShape(); paragraph.getCharShape().addParaCharShape(0, 0);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(bytes(hwp)), null)).getMessage().contains("160,000"));
        var compressed = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.BEST_SPEED, true);
        try (var zip = new DeflaterOutputStream(compressed, deflater)) {
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 17000; i++) zip.write(chunk);
        } finally { deflater.end(); }
        byte[] bomb = replaceStream(bytes(notice(true)), "DocInfo", compressed.toByteArray());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(bomb), null)).getMessage().contains("128MB"));
    }

    @Test void oversizedSingleParagraphIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        var hwp = notice(true);
        hwp.getBodyText().getSectionList().get(0).getParagraph(0).getText().addString("가".repeat(70000));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> documents.normalize(upload(bytes(hwp)), null)).getMessage().contains("문단"));
    }

    @Test void optionalRealNoticeCanBeVerifiedWithoutPersonalPathsInTests() throws Exception {
        String path = System.getProperty("actiondoc.hwpFixture");
        Assumptions.assumeTrue(path != null);
        var doc = documents.normalize(upload(Files.readAllBytes(Path.of(path))), null);
        assertTrue(doc.characters() > 100);
        assertEquals("HWP", doc.type());
        String expected = System.getProperty("actiondoc.hwpExpected", "");
        if (!expected.isEmpty()) assertTrue(doc.markdown().contains(expected));
        var report = new AnalysisService(new ObjectMapper()).analyze(doc, "", "local", false);
        assertFalse(report.sections().isEmpty());
    }

    private static byte[] header(byte[] original, int flags) throws Exception {
        var fs = new POIFSFileSystem(new ByteArrayInputStream(original));
        byte[] header;
        try (var input = fs.createDocumentInputStream("FileHeader")) { header = input.readAllBytes(); }
        var buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(36, buffer.getInt(36) | flags);
        return header;
    }

    private static byte[] replaceStream(byte[] original, String stream, byte[] replacement) throws Exception {
        var fs = new POIFSFileSystem(new ByteArrayInputStream(original));
        fs.getRoot().getEntry(stream).delete();
        fs.createDocument(new ByteArrayInputStream(replacement), stream);
        var out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
        return out.toByteArray();
    }
}
