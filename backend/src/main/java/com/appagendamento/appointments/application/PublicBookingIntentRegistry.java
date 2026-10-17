package com.appagendamento.appointments.application;

import java.util.Optional;
import java.util.UUID;

public interface PublicBookingIntentRegistry {

  PublicBookingIntent bind(PublicBookingIntent intent);

  Optional<PublicBookingIntent> findByKey(UUID tenantId, String commandScope, String keyDigest);

  Optional<PublicBookingIntent> findBySlugAndRecoveryDigest(String slug, String recoveryDigest);

  Optional<PublicAppointmentReceipt> findCompletedReceipt(PublicBookingIntent intent);
}
