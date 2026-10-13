package com.campusconnect.core.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as the JSON body the frontend expects (middleware/ErrorHandler.js): expected errors keep
 * their status and message; unexpected ones are logged and returned as a generic 500 in production.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern DUPLICATE_KEY = Pattern.compile("dup key: \\{ ?([\\w.]+): \"?([^\"}]*)\"? ?\\}");

    private final boolean production;

    public GlobalExceptionHandler(Environment environment) {
        this.production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @ExceptionHandler(AppException.class)
    ResponseEntity<ErrorBody> app(AppException error) {
        return ResponseEntity.status(error.getStatus()).body(ErrorBody.of(error.getStatus(), error.getMessage(), error.getCode(), error.getDetails()));
    }

    /** Request-body validation: express-validator messages joined with ". ". */
    @ExceptionHandler(BindException.class)
    ResponseEntity<ErrorBody> bind(BindException error) {
        // Field order of the request record = rule order of the original validators.
        Object target = error.getBindingResult().getTarget();
        java.util.List<String> order = target == null ? java.util.List.of()
                : java.util.Arrays.stream(target.getClass().getDeclaredFields()).map(java.lang.reflect.Field::getName).toList();
        String message = error.getBindingResult().getAllErrors().stream()
                .sorted(java.util.Comparator.comparingInt(e -> {
                    if (!(e instanceof FieldError field)) {
                        return Integer.MAX_VALUE;
                    }
                    int index = order.indexOf(field.getField().split("[.\\[]")[0]);
                    return index < 0 ? Integer.MAX_VALUE - 1 : index;
                }))
                .map(e -> e instanceof FieldError field && field.getDefaultMessage() == null ? "Invalid " + field.getField() : e.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(". "));
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> methodValidation(HandlerMethodValidationException error) {
        String message = error.getAllErrors().stream().map(e -> e.getDefaultMessage()).distinct().collect(Collectors.joining(". "));
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorBody> constraint(ConstraintViolationException error) {
        String message = error.getConstraintViolations().stream().map(v -> v.getMessage()).distinct().collect(Collectors.joining(". "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException error) {
        return badRequest("Malformed JSON body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException error) {
        return badRequest("Invalid " + error.getName() + ": " + error.getValue());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorBody> missingParameter(MissingServletRequestParameterException error) {
        return badRequest(error.getParameterName() + " is required");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(ErrorBody.of(413, "File is too large", ErrorCode.UPLOAD_ERROR));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorBody> multipart(MultipartException error) {
        return ResponseEntity.status(400).body(ErrorBody.of(400, "Upload failed: send the file as multipart/form-data", ErrorCode.UPLOAD_ERROR));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException error) {
        return badRequest("Unsupported content type");
    }

    /** Unique index violations become 409 like Mongoose's code 11000. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorBody> duplicate(DuplicateKeyException error) {
        String field = "value";
        String value = "";
        Matcher matcher = DUPLICATE_KEY.matcher(String.valueOf(error.getMessage()));
        if (matcher.find()) {
            field = matcher.group(1);
            value = matcher.group(2);
        }
        String message = "Duplicate value '" + value + "' for field '" + field + "'. Please use another value.";
        return ResponseEntity.status(409).body(ErrorBody.of(409, message, ErrorCode.CONFLICT));
    }

    /** Unknown routes and unsupported methods: the same 404 the Express app gives. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorBody> notFound(Exception error, HttpServletRequest request) {
        String message = "Cannot find " + request.getMethod() + " " + request.getRequestURI() + " on this server";
        return ResponseEntity.status(404).body(ErrorBody.of(404, message, null));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorBody> authentication(AuthenticationException error) {
        return ResponseEntity.status(401).body(ErrorBody.of(401, "Authentication required", ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> denied(AccessDeniedException error) {
        return ResponseEntity.status(403).body(ErrorBody.of(403, "You do not have permission to perform this action", ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), error);
        String message = production ? "Something went wrong" : String.valueOf(error.getMessage());
        return ResponseEntity.status(500).body(new ErrorBody(false, "error", message, null, null));
    }

    private static ResponseEntity<ErrorBody> badRequest(String message) {
        return ResponseEntity.status(400).body(ErrorBody.of(400, message == null || message.isBlank() ? "Invalid request" : message, ErrorCode.VALIDATION_ERROR));
    }

    /** For filters that must answer before the MVC layer (rate limits, authentication). */
    public static Map<String, Object> bodyMap(int status, String message, ErrorCode code) {
        return Map.of("success", false, "status", status >= 400 && status < 500 ? "fail" : "error", "message", message, "errorCode", code == null ? "" : code.name());
    }
}
