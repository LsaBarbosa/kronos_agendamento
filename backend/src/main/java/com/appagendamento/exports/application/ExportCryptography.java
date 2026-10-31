package com.appagendamento.exports.application;

public interface ExportCryptography {

  boolean available();

  EncryptedData encrypt(byte[] plaintext, String purpose, String tenantId, String exportId);

  byte[] decrypt(byte[] ciphertext, String keyId, String purpose, String tenantId, String exportId);

  record EncryptedData(String keyId, byte[] ciphertext) {
    public EncryptedData {
      ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext.clone();
    }
  }
}
