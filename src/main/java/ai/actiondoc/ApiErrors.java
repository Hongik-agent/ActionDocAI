package ai.actiondoc;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.*;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large() { return ResponseEntity.status(413).body(Map.of("error",DocumentLimits.FILE_SIZE_MESSAGE)); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e) {
        if(e instanceof org.springframework.http.converter.HttpMessageNotReadableException) return ResponseEntity.badRequest().body(Map.of("error","입력 형식을 확인해 주세요."));
        return ResponseEntity.status(500).body(Map.of("error","요청을 처리하지 못했습니다. 저장 여부를 확인한 후 다시 시도해 주세요."));
    }
}
