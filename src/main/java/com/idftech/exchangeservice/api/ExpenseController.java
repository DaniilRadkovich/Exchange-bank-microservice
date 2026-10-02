package com.idftech.exchangeservice.api;

import com.idftech.exchangeservice.api.dto.CreateLimitRequest;
import com.idftech.exchangeservice.api.dto.ExceededTransactionResponse;
import com.idftech.exchangeservice.api.dto.LimitResponse;
import com.idftech.exchangeservice.api.dto.TransactionRequest;
import com.idftech.exchangeservice.api.dto.TransactionResponse;
import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.LimitQueryService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Клиентский API (ТЗ, требование 1Б): приём транзакций, установление лимита, чтение лимитов и
 * списка превышений.
 *
 * <p>Два контура API из ТЗ разведены по URL: {@code /api/v1/transactions} — интеграция с
 * банковскими сервисами, остальные методы — внешний клиент.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Расходы и лимиты", description = "Приём операций и контроль месячных лимитов расходов")
@Validated
public class ExpenseController {

  private final TransactionIntakeService intakeService;
  private final LimitCommandService limitCommandService;
  private final LimitQueryService limitQueryService;

  public ExpenseController(
      TransactionIntakeService intakeService,
      LimitCommandService limitCommandService,
      LimitQueryService limitQueryService) {
    this.intakeService = intakeService;
    this.limitCommandService = limitCommandService;
    this.limitQueryService = limitQueryService;
  }

  @PostMapping("/transactions")
  @Operation(
      summary = "Приём расходной операции",
      description =
          "Транзакция сохраняется сразу и возвращается со статусом PENDING: перевод в USD и расчёт "
              + "флага limit_exceeded выполняются асинхронно, чтобы приём не зависел от доступности "
              + "внешнего API курсов.")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "Транзакция принята и поставлена в обработку"),
    @ApiResponse(responseCode = "400", description = "Ошибка валидации входных данных"),
    @ApiResponse(responseCode = "422", description = "Данные корректны по формату, но неприемлемы"),
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервиса")
  })
  public ResponseEntity<TransactionResponse> acceptTransaction(
      @Valid @RequestBody TransactionRequest request) {
    UUID transactionId = intakeService.resolveTransactionId(request.transactionId());
    ExpenseTransaction accepted = intakeService.accept(
        transactionId,
        request.accountFrom(),
        request.accountTo(),
        request.currencyShortname().toUpperCase(java.util.Locale.ROOT),
        request.sum(),
        ExpenseCategory.fromCode(request.expenseCategory()),
        request.datetime());
    return ResponseEntity.accepted().body(TransactionResponse.of(accepted));
  }

  @GetMapping("/transactions/{transactionId}")
  @Operation(
      summary = "Состояние расчёта транзакции",
      description = "Позволяет узнать применённый курс и флаг limit_exceeded после асинхронной обработки.")
  public TransactionResponse getTransaction(@PathVariable UUID transactionId) {
    return intakeService
        .findById(transactionId)
        .map(TransactionResponse::of)
        .orElseThrow(() -> new com.idftech.exchangeservice.application.exception.ResourceNotFoundException(
            "transaction", transactionId.toString()));
  }

  @PostMapping("/limits")
  @Operation(
      summary = "Установка нового месячного лимита",
      description =
          "Дата установки проставляется сервисом из текущего времени, клиент её не задаёт. Обновление "
              + "существующего лимита не поддерживается: новый лимит — новая запись.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Лимит установлен"),
    @ApiResponse(responseCode = "400", description = "Ошибка валидации входных данных"),
    @ApiResponse(
        responseCode = "409",
        description = "На этот момент установки лимит уже стоит: пара «счёт + категория + момент» занята")
  })
  public ResponseEntity<LimitResponse> createLimit(@Valid @RequestBody CreateLimitRequest request) {
    ExpenseLimit created = limitCommandService.createLimit(
        request.accountFrom(),
        ExpenseCategory.fromCode(request.expenseCategory()),
        request.limitSum());
    return ResponseEntity.status(HttpStatus.CREATED).body(LimitResponse.of(created));
  }

  @GetMapping("/limits")
  @Operation(
      summary = "Все лимиты счёта",
      description = "Возвращает установленные клиентом лимиты, новые первыми.")
  public List<LimitResponse> getLimits(
      @Parameter(description = "Банковский счёт клиента", example = "0000000123")
          @RequestParam("account_from")
          @Pattern(regexp = "\\d{10}", message = "must contain exactly 10 digits")
          String accountFrom) {
    return LimitResponse.ofSpent(limitQueryService.findAllLimitsWithSpent(accountFrom));
  }

  @GetMapping("/limits/exceeded")
  @Operation(
      summary = "Транзакции, превысившие лимит (ТЗ п.6)",
      description =
          "Возвращает превышения вместе с параметрами превышенного лимита: датой установки, суммой и "
              + "валютой.")
  public List<ExceededTransactionResponse> getExceeded(
      @Parameter(description = "Банковский счёт клиента", example = "0000000123")
          @RequestParam("account_from")
          @Pattern(regexp = "\\d{10}", message = "must contain exactly 10 digits")
          String accountFrom) {
    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(accountFrom);
    return ExceededTransactionResponse.of(exceeded);
  }
}
