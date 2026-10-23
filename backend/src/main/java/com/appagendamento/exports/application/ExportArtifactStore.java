package com.appagendamento.exports.application;

public interface ExportArtifactStore {

  boolean available();

  /**
   * Implementations must durably fence both writes and deletes per object key. Writes older than
   * the latest accepted operation are rejected; repeating the same live version and bytes is
   * idempotent. A delete records a tombstone at its fencing version, so a late write at that or an
   * older version cannot recreate the object.
   */
  void put(String objectKey, long fencingVersion, byte[] encryptedContent);

  byte[] get(String objectKey);

  void delete(String objectKey, long fencingVersion);
}
