package ai.actiondoc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import kr.dogfoot.hwplib.org.apache.poi.poifs.filesystem.*;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractOption;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;

/** Reads HWP 5 documents without running their scripts or opening embedded files. */
final class HwpTextExtractor {
    private static final byte[] OLE_SIGNATURE = {(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
    private static final class InvalidHwp extends IllegalArgumentException {
        InvalidHwp(String message) { super(message); }
    }
    private static final int MAX_EXPANDED_BYTES = 128 * 1024 * 1024;
    static final String WARNING = "HWP 본문과 표·글상자의 텍스트를 추출했습니다. 원본 페이지 번호는 제공하지 않으며 문단 ID로 근거를 표시합니다. 표의 행·열 관계와 금액·각주는 원본과 대조해 주세요. 이미지 속 글자와 연결 주소만 있는 하이퍼링크는 추출하지 않습니다.";

    static String extract(byte[] data) {
        if (data.length < 8 || !Arrays.equals(Arrays.copyOf(data, 8), OLE_SIGNATURE))
            throw new InvalidHwp("HWP 5 형식의 한글 문서만 지원합니다. 한글에서 HWP 5 또는 PDF로 다시 저장해 주세요.");
        try {
            validate(data);
            var hwp = HWPReader.fromInputStream(new ByteArrayInputStream(data));
            // The default includes paragraph numbering and text inside tables and text boxes.
            var option = new TextExtractOption();
            option.setWithControlChar(true); // Preserve manual line breaks and tabs.
            String text = TextExtractor.extract(hwp, option);
            if (text == null || text.replace("\u0000", "").isBlank())
                throw new InvalidHwp("HWP에서 읽을 수 있는 본문을 찾지 못했습니다. 이미지 문서는 PDF로 저장해 올리면 자동 OCR로 처리할 수 있습니다.");
            if (text.length() > 160000)
                throw new InvalidHwp("본문은 160,000자 이하여야 합니다. 문서를 나누어 입력해 주세요.");
            return text;
        } catch (InvalidHwp e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidHwp("HWP를 읽지 못했습니다. 손상 여부를 확인하거나 한글에서 HWP 5 또는 PDF로 다시 저장해 주세요.");
        }
    }

    private static void validate(byte[] data) throws Exception {
        var fs = new POIFSFileSystem(new ByteArrayInputStream(data));
        byte[] header;
        try (var input = fs.createDocumentInputStream("FileHeader")) { header = input.readNBytes(256); }
        if (header.length != 256 || !new String(header, 0, 17, StandardCharsets.US_ASCII).equals("HWP Document File") || header[35] != 5)
            throw new InvalidHwp("올바른 HWP 5 문서가 아닙니다. 한글에서 HWP 5 또는 PDF로 다시 저장해 주세요.");
        int flags = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36);
        if ((flags & 2) != 0)
            throw new InvalidHwp("암호가 설정된 HWP는 지원하지 않습니다. 암호를 제거하거나 PDF로 저장해 주세요.");
        if ((flags & 4) != 0)
            throw new InvalidHwp("배포용으로 보호된 HWP는 지원하지 않습니다. 보호되지 않은 원본이나 PDF를 올려 주세요.");
        if ((flags & (1 << 4)) != 0 || (flags & (1 << 8)) != 0 || (flags & (1 << 10)) != 0)
            throw new InvalidHwp("DRM 또는 인증서로 보호된 HWP는 지원하지 않습니다. 보호되지 않은 원본이나 PDF를 올려 주세요.");
        // Bound expansion before the library allocates the decompressed streams.
        checkExpansion(fs.getRoot(), "", new long[]{0}, (flags & 1) != 0);
    }

    private static void checkExpansion(DirectoryEntry directory, String path, long[] expanded, boolean compressed) throws IOException {
        for (var entry : directory) {
            String name = path + entry.getName();
            if (entry instanceof DirectoryEntry child) {
                if (path.isEmpty() && (name.equals("BodyText") || name.equals("BinData") || name.equals("Scripts")))
                    checkExpansion(child, name + "/", expanded, compressed);
            } else if (entry instanceof DocumentEntry document && (name.equals("DocInfo") || name.startsWith("BodyText/") || name.startsWith("BinData/") || name.startsWith("Scripts/"))) {
                try (var input = new DocumentInputStream(document)) {
                    byte[] bytes = input.readAllBytes();
                    boolean body = name.startsWith("BodyText/");
                    if (compressed || name.startsWith("BinData/"))
                        bytes = checkDeflate(bytes, expanded, name.startsWith("BinData/"), body);
                    if (body) checkTextRecords(bytes);
                }
            }
        }
    }

    private static byte[] checkDeflate(byte[] bytes, long[] expanded, boolean mayBeUncompressed, boolean retain) throws IOException {
        var inflater = new Inflater(true);
        var output = retain ? new ByteArrayOutputStream() : null;
        try {
            inflater.setInput(bytes);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count;
                try { count = inflater.inflate(buffer); }
                catch (DataFormatException e) {
                    if (mayBeUncompressed) return bytes;
                    throw new IOException("Invalid HWP compressed stream", e);
                }
                expanded[0] += count;
                if (expanded[0] > MAX_EXPANDED_BYTES)
                    throw new InvalidHwp("HWP 압축 해제 크기가 128MB를 초과합니다. 문서를 나누거나 PDF로 저장해 주세요.");
                if (retain) output.write(buffer, 0, count);
                if (count == 0 && !inflater.finished()) {
                    if (mayBeUncompressed) return bytes;
                    throw new IOException("Incomplete HWP compressed stream");
                }
            }
            return retain ? output.toByteArray() : bytes;
        } finally { inflater.end(); }
    }

    private static void checkTextRecords(byte[] bytes) throws IOException {
        var records = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (records.hasRemaining()) {
            if (records.remaining() < 4) throw new IOException("Truncated HWP record");
            int header = records.getInt();
            long size = header >>> 20;
            if (size == 0xfff) {
                if (records.remaining() < 4) throw new IOException("Truncated HWP record size");
                size = Integer.toUnsignedLong(records.getInt());
            }
            if (size > records.remaining()) throw new IOException("Truncated HWP record data");
            // hwplib's paragraph extractor stops at character index 0xffff. Never silently truncate.
            if ((header & 0x3ff) == 67 && size > 131072)
                throw new InvalidHwp("HWP에 너무 긴 문단이 있습니다. 한 문단을 65,000자 이하로 나누거나 PDF로 저장해 주세요.");
            records.position(records.position() + (int) size);
        }
    }
}
