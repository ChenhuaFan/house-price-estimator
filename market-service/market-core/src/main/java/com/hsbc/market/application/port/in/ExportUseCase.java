package com.hsbc.market.application.port.in;

import com.hsbc.market.domain.ExportDomain.Content;
import com.hsbc.market.domain.ExportDomain.CreateCommand;
import com.hsbc.market.domain.ExportDomain.Job;
import java.util.UUID;

public interface ExportUseCase {
  Job create(CreateCommand command);

  Job get(UUID exportId);

  void cancel(UUID exportId);

  Content locateContent(UUID exportId);
}
