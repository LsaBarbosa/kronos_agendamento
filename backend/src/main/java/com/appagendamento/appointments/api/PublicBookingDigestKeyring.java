package com.appagendamento.appointments.api;

import java.util.Optional;

public interface PublicBookingDigestKeyring {

  String activeKeyId();

  byte[] activeKey();

  Optional<byte[]> key(String keyId);
}
