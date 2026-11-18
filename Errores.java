package pe.edu.esfap.portal;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Respuestas de error controladas: sin trazas ni detalles internos. */
@RestControllerAdvice
public class Errores {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> grande() {
        return ResponseEntity.status(413).body(Json.m("error", "El archivo excede el tamaño máximo permitido."));
    }
}
