package com.hsbc.market.model.persistence;

import java.util.UUID;

/** Database projection for an immutable dataset version. */
public record DatasetVersionRecord(UUID id, String contentSha256) {}
