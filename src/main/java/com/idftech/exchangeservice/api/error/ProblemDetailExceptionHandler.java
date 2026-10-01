package com.idftech.exchangeservice.api.error;

import com.idftech.exchangeservice.application.exception.ResourceNotFoundException;
import com.idftech.exchangeservice.application.exception.UnprocessableEntityException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Единый формат ошибок — {@code application/problem+json} (RFC 9457, {@code ProblemDetail}).
 *
 * <p>Формат один для всех ошибок: клиенту достаточно разбирать {@code type}, {@code title},
 * {@code status}, {@code detail} и расширения. Коды заданы собственным URI-пространством
 * {@code /problems/...}, чтобы их можно было документировать в OpenAPI.
 */
/**
 * Порядок важнее, чем кажется.
 *
 * <p>Spring Boot 4 регистрирует собственный {@code ProblemDetailsExceptionHandler}, который
 * обрабатывает вообще любое исключение. Если у него нет явного порядка, он оказывается первым в
 * списке обработчиков, и наш {@code @ExceptionHandler} для конкретных типов просто не вызывается:
 * клиент получает generic-ответ {@code "Invalid request content."} вместо перечня полей и кода
 * {@code reason}. Поэтому наш advice получает приоритет через {@link Ordered#HIGHEST_PRECEDENCE}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);

  private static final URI VALIDATION_TYPE = URI.create("https://exchangeservice.example.com/problems/validation");
  private static final URI NOT_FOUND_TYPE = URI.create("https://exchangeservice.example.com/problems/not-found");
  private static final URI UNPROCESSABLE_TYPE = URI.create("https://exchangeservice.example.com/problems/unprocessable");
  private static final URI CONFLICT_TYPE = URI.create("https://exchangeservice.example.com/problems/conflict");
  private static final URI INTERNAL_TYPE = URI.create("https://exchangeservice.example.com/problems/internal");

  /** Ошибки валидации тела запроса: 400 с перечнем полей. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleBodyValidation(MethodArgumentNotValidException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Параметры запроса не прошли валидацию");
    problem.setType(VALIDATION_TYPE);
    problem.setTitle("Ошибка валидации");
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** Ошибки валидации параметров запроса и методов: 400. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleParameterValidation(ConstraintViolationException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Параметры запроса не прошли валидацию");
    problem.setType(VALIDATION_TYPE);
    problem.setTitle("Ошибка валидации");
    List<Map<String, String>> errors =
        java.util.Arrays.stream(e.getConstraintViolations().toArray(ConstraintViolation[]::new))
            .map(
                violation ->
                    Map.of(
                        "field", String.valueOf(violation.getPropertyPath()),
                        "message", violation.getMessage()))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /** Некорректный JSON или нечитаемое тело: 400. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Тело запроса не удалось разобрать");
    problem.setType(VALIDATION_TYPE);
    problem.setTitle("Некорректный запрос");
    return problem;
  }

  /** Отсутствующий ресурс: 404. */
  @ExceptionHandler(ResourceNotFoundException.class)
  public ProblemDetail handleNotFound(ResourceNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    problem.setType(NOT_FOUND_TYPE);
    problem.setTitle("Ресурс не найден");
    problem.setProperty("resource", e.getResourceType());
    problem.setProperty("identifier", e.getIdentifier());
    return problem;
  }

  /** Данные формально корректны, но неприемлемы: 422. */
  @ExceptionHandler(UnprocessableEntityException.class)
  public ProblemDetail handleUnprocessable(UnprocessableEntityException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    problem.setType(UNPROCESSABLE_TYPE);
    problem.setTitle("Данные неприемлемы");
    problem.setProperty("reason", e.getReason());
    return problem;
  }

  /** Нарушение ограничений целостности: 409. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail handleConflict(DataIntegrityViolationException e) {
    log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Операция нарушает ограничения целостности данных");
    problem.setType(CONFLICT_TYPE);
    problem.setTitle("Конфликт данных");
    return problem;
  }

  /** Всё остальное: 500 без внутренних деталей в ответе. */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервиса, попробуйте позже");
    problem.setType(INTERNAL_TYPE);
    problem.setTitle("Внутренняя ошибка");
    return problem;
  }
}
