package com.idftech.exchangeservice.api.error;

import com.idftech.exchangeservice.application.exception.ConflictException;
import com.idftech.exchangeservice.application.exception.ResourceNotFoundException;
import com.idftech.exchangeservice.application.exception.UnprocessableEntityException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.RecordComponent;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

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
 *
 * <p>Обратная сторона приоритета: advice, который перехватывает {@link Exception}, обходит и
 * {@code DefaultHandlerExceptionResolver}, поэтому стандартные ошибки Spring MVC — 400, 404, 405, 415 —
 * разбирает он же. Без явного {@link #handleFrameworkError} они все уезжали бы в 500. Новые типы
 * ошибок MVC обязаны попадать в этот метод, а не в {@link #handleUnexpected}.
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
  private static final URI NOT_ALLOWED_TYPE =
      URI.create("https://exchangeservice.example.com/problems/method-not-allowed");
  private static final URI UNSUPPORTED_MEDIA_TYPE_TYPE =
      URI.create("https://exchangeservice.example.com/problems/unsupported-media-type");

  /**
   * Ошибки протокола, которые Spring MVC умеет выражать сам: 400, 404, 405, 406, 415.
   *
   * <p>Advice объявлен первым, а {@link #handleUnexpected(Exception)} ловит всё, поэтому стандартный
   * {@code DefaultHandlerExceptionResolver} до клиента не доходит. Здесь статусы выставляются явно,
   * чтобы клиент получал 404 на неверный путь, а не 500.
   */
  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    TypeMismatchException.class,
    HandlerMethodValidationException.class,
    MethodValidationException.class,
    ErrorResponseException.class,
    NoResourceFoundException.class,
    HttpRequestMethodNotSupportedException.class,
    HttpMediaTypeNotSupportedException.class,
    HttpMediaTypeNotAcceptableException.class
  })
  public ProblemDetail handleFrameworkError(Exception e) {
    HttpStatus status = frameworkStatus(e);
    if (status.is5xxServerError()) {
      log.error("Framework exception resolved to {}", status.value(), e);
    } else {
      log.debug("Framework exception resolved to {}: {}", status.value(), e.getMessage());
    }
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(status, "Запрос не может быть обработан: " + frameworkDetail(e));
    problem.setType(frameworkType(status));
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }

  private static HttpStatus frameworkStatus(Exception e) {
    return switch (e) {
      case ErrorResponseException error -> HttpStatus.valueOf(error.getStatusCode().value());
      case HttpRequestMethodNotSupportedException ignored -> HttpStatus.METHOD_NOT_ALLOWED;
      case HttpMediaTypeNotAcceptableException ignored -> HttpStatus.NOT_ACCEPTABLE;
      case HttpMediaTypeNotSupportedException ignored -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
      case NoResourceFoundException ignored -> HttpStatus.NOT_FOUND;
      case MissingServletRequestParameterException missing ->
          HttpStatus.BAD_REQUEST; // не передан обязательный параметр запроса
      default -> HttpStatus.BAD_REQUEST; // TypeMismatch и нарушения @Validated
    };
  }

  private static URI frameworkType(HttpStatus status) {
    return switch (status) {
      case NOT_FOUND -> NOT_FOUND_TYPE;
      case METHOD_NOT_ALLOWED -> NOT_ALLOWED_TYPE;
      case NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE_TYPE;
      default -> VALIDATION_TYPE;
    };
  }

  private static String frameworkDetail(Exception e) {
    return switch (e) {
      case MissingServletRequestParameterException missing -> "не передан параметр " + missing.getParameterName();
      case ErrorResponseException error -> error.getBody().getDetail();
      case HttpRequestMethodNotSupportedException notAllowed -> notAllowed.getMethod() + " не поддерживается";
      case HttpMediaTypeNotSupportedException notSupported ->
          "Content-Type " + notSupported.getContentType() + " не поддерживается";
      case HttpMediaTypeNotAcceptableException notAcceptable -> "Accept " + notAcceptable.getSupportedMediaTypes() + " недостижим";
      case NoResourceFoundException ignored -> "нет такого ресурса";
      default -> e.getMessage();
    };
  }

  /**
   * Ошибки валидации тела запроса: 400 с перечнем полей.
   *
   * <p>Поле называется так, как его видит клиент, а не как оно называется в Java: см. {@link
   * #jsonFieldName}.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleBodyValidation(MethodArgumentNotValidException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Параметры запроса не прошли валидацию");
    problem.setType(VALIDATION_TYPE);
    problem.setTitle("Ошибка валидации");
    Class<?> payloadType = e.getParameter() != null ? e.getParameter().getParameterType() : null;
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    Map.of(
                        "field", jsonFieldName(payloadType, error.getField()),
                        "message", String.valueOf(error.getDefaultMessage())))
            .toList();
    problem.setProperty("errors", errors);
    return problem;
  }

  /**
   * Имя поля в терминах JSON, то есть таким, каким его прислал клиент.
   *
   * <p>Bean Validation называет поле по имени компонента записи ({@code transactionId}), а клиент
   * присылает и читает {@code transaction_id}. Ошибка с чужим именем поля бесполезна: исправлять
   * нужно ровно то, что прислано. Имя берётся из {@code @JsonProperty} того же компонента, поэтому
   * переименование поля в DTO не рассинхронизирует текст ошибки с контрактом. Если разобрать имя не
   * удалось, возвращается исходное: ошибка с неверным именем поля лучше, чем ошибка без имени.
   */
  private static String jsonFieldName(Class<?> payloadType, String javaName) {
    if (payloadType != null && payloadType.isRecord()) {
      for (RecordComponent component : payloadType.getRecordComponents()) {
        JsonProperty jsonProperty = component.getAccessor().getAnnotation(JsonProperty.class);
        if (component.getName().equals(javaName) && jsonProperty != null
            && !jsonProperty.value().isEmpty()) {
          return jsonProperty.value();
        }
      }
    }
    return javaName;
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
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, unreadableDetail(e));
    problem.setType(VALIDATION_TYPE);
    problem.setTitle("Некорректный запрос");
    return problem;
  }

  /**
   * Причина отказа называет поле, когда Jackson её сообщил.
   *
   * <p>Неизвестное поле — самая частая из причин, и клиент без названия поля не знает, что
   * исправлять: пришлось бы перебирать тело по частям. Текст Jackson сюда целиком не
   * переносится — в нём встречаются имена Java-типов и куски исходного тела, а {@code detail}
   * ошибки должен быть предсказуемым.
   */
  private String unreadableDetail(HttpMessageNotReadableException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof UnrecognizedPropertyException unknown) {
        return "Неизвестное поле в теле запроса: " + unknown.getPropertyName();
      }
    }
    return "Тело запроса не удалось разобрать";
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

  /** Запись уже существует в запрошенном состоянии: 409. */
  @ExceptionHandler(ConflictException.class)
  public ProblemDetail handleConflict(ConflictException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    problem.setType(CONFLICT_TYPE);
    problem.setTitle("Конфликт данных");
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

  /**
   * Всё, что не распознал ни {@link #handleFrameworkError}, ни доменные обработчики: 500 без
   * внутренних деталей в ответе.
   */
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
