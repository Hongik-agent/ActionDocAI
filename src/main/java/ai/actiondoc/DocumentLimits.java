package ai.actiondoc;

final class DocumentLimits {
    static final int MAX_PDF_PAGES = 50;
    static final int MAX_FILE_MB = 50;
    static final int MAX_FILE_BYTES = MAX_FILE_MB * 1024 * 1024;
    static final String FILE_SIZE_MESSAGE = "파일은 " + MAX_FILE_MB + "MB 이하여야 합니다.";
    private DocumentLimits() {}
}
