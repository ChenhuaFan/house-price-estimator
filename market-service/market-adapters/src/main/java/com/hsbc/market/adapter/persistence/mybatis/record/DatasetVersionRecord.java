package com.hsbc.market.adapter.persistence.mybatis.record;

import java.util.UUID;

public record DatasetVersionRecord(UUID id, String contentSha256) {}
