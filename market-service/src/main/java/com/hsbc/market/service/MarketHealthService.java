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

  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public boolean catalogReadable() {
    return marketReadDao.catalogReadable();
  }

  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public boolean operationsWritable() {
    return exportJobDao.operationsWritable();
  }
}
