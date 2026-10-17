package com.appagendamento.appointments.application;

import com.appagendamento.appointments.domain.PublicBookingRecoveryTemporarilyUnavailableException;
import com.appagendamento.appointments.domain.PublicBookingRecoveryUnavailableException;
import com.appagendamento.idempotency.application.IdempotencyKey;
import java.time.Clock;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetPublicBookingIntent {

  private final PublicBookingIntentRegistry intents;
  private final Clock clock;

  public GetPublicBookingIntent(PublicBookingIntentRegistry intents, Clock clock) {
    this.intents = intents;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicBookingRecoveryState execute(String slug, String recoveryCapability) {
    if (recoveryCapability == null || !recoveryCapability.matches("[A-Za-z0-9_-]{43}")) {
      throw new PublicBookingRecoveryUnavailableException();
    }
    try {
      PublicBookingIntent intent =
          intents
              .findBySlugAndRecoveryDigest(slug, IdempotencyKey.digest(recoveryCapability))
              .filter(candidate -> candidate.expiresAt().isAfter(clock.instant()))
              .orElseThrow(PublicBookingRecoveryUnavailableException::new);
      return intents
          .findCompletedReceipt(intent)
          .map(PublicBookingRecoveryState::completed)
          .orElseGet(PublicBookingRecoveryState::preparedUnknown);
    } catch (DataAccessException exception) {
      throw new PublicBookingRecoveryTemporarilyUnavailableException(exception);
    }
  }
}
