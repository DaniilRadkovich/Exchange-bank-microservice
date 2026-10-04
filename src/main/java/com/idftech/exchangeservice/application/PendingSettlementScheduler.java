package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.config.SettlementProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Фоновая дорасчётка транзакций, оставшихся в статусе {@code PENDING} (ТЗ: внешний API может быть
 * недоступен, данные терять нельзя).
 *
 * <h2>Два повода сделать проход, а не один</h2>
 *
 * <p>Проход запускается по двум событиям, и разница между ними — в задержке, которую видит клиент:
 *
 * <ul>
 *   <li><b>Приём операции</b> ({@link TransactionAccepted}) — проход ставится в очередь сразу после
 *       фиксации строки {@code PENDING}. Без этого флаг {@code limit_exceeded} появлялся бы только
 *       через {@code exchange.settlement.retry-delay}: клиент, отправивший операцию и сразу
 *       запросивший превышения, уходил бы с ответом «превышений нет» на полминуты после приёма.
 *   <li><b>Плановый проход</b> ({@code @Scheduled}) — страховка для всего, что kick не покрыл:
 *       транзакция, дошедшая до приёма при выключенной фоновой обработке, операция, принятая до
 *       сбоя сервиса, и пачка, не уложившаяся в один проход.
 * </ul>
 *
 * <p>Плановый проход остаётся основным: kick не заменяет его, а только снимает ожидание. Ставить
 * проход в очередь на каждый приём нельзя — тогда пачка операций одной валюты разошлась бы на
 * пачку платных запросов к внешнему API, а дедупликация по паре «валюта + дата» работает только
 * внутри одного прохода.
 *
 * <h2>Что происходит на потоке HTTP-запроса</h2>
 *
 * <p>Ничего: обработчик только ставит флаг и кладёт задачу в пул расчёта. Сам проход (взятие пачки,
 * запросы курсов, расчёт флагов) выполняется на виртуальном потоке {@code settlementExecutor}, то
 * есть ровно там же, где и плановый проход. Иначе волна приёмов заняла бы соединения PostgreSQL
 * вперемешку с приёмом следующих операций — ровно тот случай, который закрыт правилом 10
 * ({@code exchange.settlement.parallelism} и {@code SettlementPoolGuard}).
 *
 * <p>Задача, ожидающая своей очереди, удерживает одно разрешение пула, пока ждёт завершения
 * дочерних расчётов. Это уменьшает число одновременных расчётов на единицу и на инвариант
 * «параллелизм + 4 ≤ размер пула» не влияет.
 *
 * <p>Запускается на виртуальных потоках: обработка внешнего API — это ожидание, а не вычисления,
 * поэтому блокирующие вызовы здесь уместны.
 *
 * <h2>Один проход за раз, но без потерянных транзакций</h2>
 *
 * <p>Параллельных проходов не бывает: {@code claimPending} не дал бы взять одну строку дважды, но
 * дедупликация курсов видит только свой проход, и два прохода отправили бы платные запросы по одному
 * ключу «валюта + дата» дважды. Взамен проход, увидевший запрос во время своей работы, делает ещё
 * один круг: транзакция, принятая после взятия пачки, не должна ждать следующего планового прохода.
 *
 * <p>Отключается флагом {@code exchange.settlement.processing-enabled=false} — в тестах это делает
 * результат детерминированным, а в интеграционных тестах дорасчёт запускается явно. Выключаются оба
 * повода сразу: иначе «детерминированный» тест зависел бы от фоновой задачи, запущенной приёмом.
 *
 * <p>Пауза между проходами и размер пачки берутся из конфигурации, а не из констант: пачка на 100
 * транзакций при параллелизме 16 держит соединения PostgreSQL дольше, чем пачка на 10, и размер
 * пачки меняют под нагрузку, не пересобирая образ. Пауза в yaml объявлена один раз: default в
 * аннотации был бы вторым значением той же настройки, и его можно было бы забыть обновить.
 */
@Component
@ConditionalOnProperty(
    prefix = "exchange.settlement",
    name = "processing-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PendingSettlementScheduler {

  private static final Logger log = LoggerFactory.getLogger(PendingSettlementScheduler.class);

  private final TransactionIntakeService intakeService;
  private final SettlementProperties settlementProperties;
  private final Executor settlementExecutor;

  /** Идёт ли проход сейчас: второй не начинается, а помечает, что нужен ещё круг. */
  private final AtomicBoolean passRunning = new AtomicBoolean();

  /** Просили ли проход, который ещё не выполнен: им заканчивается цикл проходов. */
  private final AtomicBoolean passRequested = new AtomicBoolean();

  /** Поставлена ли задача в очередь: пачка приёмов не должна плодить пустые задачи. */
  private final AtomicBoolean kickQueued = new AtomicBoolean();

  public PendingSettlementScheduler(
      TransactionIntakeService intakeService,
      SettlementProperties settlementProperties,
      @Qualifier("settlementExecutor") Executor settlementExecutor) {
    this.intakeService = intakeService;
    this.settlementProperties = settlementProperties;
    this.settlementExecutor = settlementExecutor;
  }

  /** Плановый проход: пауза между ним и следующим задаёт {@code exchange.settlement.retry-delay}. */
  /**
   * Плановый проход: пауза между ним и следующим задаёт {@code exchange.settlement.retry-delay}.
   *
   * <p>Если проход уже идёт, вызов не запускает второй, а помечает, что нужен ещё один круг: транзакции,
   * принятые во время текущего прохода, иначе ждали бы следующего планового, то есть
   * {@code exchange.settlement.retry-delay}.
   */
  @Scheduled(fixedDelayString = "${exchange.settlement.retry-delay}")
  public void processPending() {
    pass();
  }

  /**
   * Проходы дорасчётки, пока идут запросы на них.
   *
   * <p>Один проход берёт пачку и считает её целиком. Транзакции, принятые после того, как пачка уже
   * взята, в неё не попадают — поэтому после каждого прохода проверяется, просил ли кто-то ещё. Проверка
   * обязательна: без неё транзакция, принятая в конце прохода, ждала бы планового прохода, то есть
   * секунды в бою и час в тесте, где пауза настроена намеренно большой.
   *
   * <p>Параллельных проходов не бывает: {@code claimPending} не дал бы взять одну строку дважды, но
   * дедупликация курсов видит только свой проход, и два прохода отправили бы платные запросы по одному
   * ключу «валюта + дата» дважды.
   *
   * <p>Гонки между «проход закончился» и «пришёл запрос» нет: флаги проверяются и снимаются в таком
   * порядке, что любой запрос либо увидит занятый {@code passRunning} и заставит текущий проход сделать
   * ещё круг, либо увидит свободный и запустит проход сам.
   *
   * @return сколько транзакций взято в обработку за все круги
   */
  public int pass() {
    if (!passRunning.compareAndSet(false, true)) {
      passRequested.set(true);
      log.debug("Settlement pass is already running; it will do one more round");
      return 0;
    }
    int processed = 0;
    try {
      do {
        passRequested.set(false);
        processed += onePass();
      } while (passRequested.get());
    } finally {
      passRunning.set(false);
      // Запрос мог прийти в окне между последней проверкой и снятием флага: тогда проход уже не
      // владеет циклом и подхватить запрос обязан новый.
      if (passRequested.get()) {
        enqueue();
      }
    }
    return processed;
  }

  /**
   * Просит проход из потока HTTP-запроса: не блокирует, работу выполняет пул расчёта.
   *
   * <p>Задача в очереди может быть только одна: пока она ждёт своей очереди, приходят десятки приёмов,
   * и каждому пришлось бы проверять флаг. Проверку делает один {@code compareAndSet}.
   *
   * <p>Отказ пула на остановке сервиса — не авария: попытку никто не потратил, транзакция осталась
   * {@code PENDING}, и её заберёт плановый проход или следующий запуск.
   */
  public void requestPass() {
    passRequested.set(true);
    enqueue();
  }

  private void enqueue() {
    if (!kickQueued.compareAndSet(false, true)) {
      log.debug("Settlement pass is already queued; it will do one more round");
      return;
    }
    try {
      settlementExecutor.execute(
          () -> {
            // Флаг снимается до прохода, а не после: приём, пришедший, пока задача ждала очереди,
            // должен суметь поставить свою.
            kickQueued.set(false);
            pass();
          });
    } catch (RejectedExecutionException e) {
      kickQueued.set(false);
      log.info("Settlement pass was not scheduled: the settlement pool is shutting down");
    }
  }

  /** Один круг: пачка транзакций берётся и считается целиком. */
  private int onePass() {
    int processed = intakeService.settlePending(settlementProperties.batchSize());
    if (processed > 0) {
      log.info("Processed {} pending transactions", processed);
    }
    return processed;
  }

  /**
   * Принятая операция запускает проход.
   *
   * <p>Слушатель срабатывает после фиксации транзакции приёма, а не во время её работы: иначе проход
   * искал бы в базе строку, которой ещё нет. Само событие — повод намекнуть, что пора считать, а не
   * указание «посчитать вот эту транзакцию»: проход забирает пачку, иначе каждый приём вёл бы
   * отдельный запрос к платному API и дедупликация по паре «валюта + дата» перестала бы работать.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onTransactionAccepted(TransactionAccepted accepted) {
    log.debug("Transaction {} accepted; scheduling a settlement pass", accepted.transactionId());
    requestPass();
  }
}
