package com.appagendamento.availability.infrastructure;

import com.appagendamento.appointments.api.AppointmentSlotValidator;
import com.appagendamento.appointments.api.ValidatedAppointmentSlot;
import com.appagendamento.appointments.domain.InvalidAppointmentTimeException;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityAppointmentSlotValidator implements AppointmentSlotValidator {

  private final CalculateAvailabilityForDateRange availability;

  public AvailabilityAppointmentSlotValidator(CalculateAvailabilityForDateRange availability) {
    this.availability = availability;
  }

  @Override
  public Optional<ValidatedAppointmentSlot> findCurrentSlot(
      UUID tenantId, UUID serviceId, OffsetDateTime requestedStart) {
    LocalDate localDate = requestedStart.toLocalDate();
    var result = availability.execute(tenantId, serviceId, localDate, localDate);
    ZoneId zone = ZoneId.of(result.timeZone());
    OffsetDateTime normalized = requestedStart.toInstant().atZone(zone).toOffsetDateTime();
    if (!normalized.equals(requestedStart)) {
      throw new InvalidAppointmentTimeException();
    }
    return result.days().stream()
        .filter(day -> day.date().equals(localDate))
        .flatMap(day -> day.slots().stream())
        .filter(slot -> slot.startsAt().toInstant().equals(requestedStart.toInstant()))
        .filter(slot -> slot.startsAt().getOffset().equals(requestedStart.getOffset()))
        .findFirst()
        .map(
            slot ->
                new ValidatedAppointmentSlot(
                    slot.startsAt(), slot.serviceEndsAt(), result.timeZone()));
  }
}
