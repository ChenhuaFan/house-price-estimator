package com.hsbc.market.service;

import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.dao.MarketReadDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs lightweight data-source probes for readiness indicators. */
@Service
public class MarketHealthService {
  private final MarketReadDao marketReadDao;
  private final ExportJobDao exportJobDao;

  public MarketHealthService(MarketReadDao marketReadDao, ExportJobDao exportJobDao) {
    this.marketReadDao = marketReadDao;
    this.exportJobDao = exportJobDao;
  }

  /** Verifies that the mutable business data and its revision can be queried. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public boolean businessDataReadable() {
    return marketReadDao.businessDataReadable();
  }

  /** Verifies that export job state is writable through the query pool. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public boolean operationsWritable() {
    return exportJobDao.operationsWritable();
  }
}
