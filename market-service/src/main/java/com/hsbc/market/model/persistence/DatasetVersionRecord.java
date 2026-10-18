package com.hsbc.market.model.persistence;

import java.util.UUID;

public record DatasetVersionRecord(UUID id, String contentSha256) {}
