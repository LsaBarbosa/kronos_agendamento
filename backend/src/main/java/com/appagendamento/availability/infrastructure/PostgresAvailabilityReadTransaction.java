package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.AvailabilityReadTransaction;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PostgresAvailabilityReadTransaction implements AvailabilityReadTransaction {

  private final TransactionTemplate transaction;

  public PostgresAvailabilityReadTransaction(PlatformTransactionManager transactionManager) {
    transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Override
  public <T> T execute(Supplier<T> query) {
    return transaction.execute(status -> query.get());
  }
}
