package com.appagendamento.exports.application;

public record AuthorizedExportDownload(
    ExportRepository.ExportDownloadArtifact artifact, String handleDigest) {}
