package com.hsbc.market;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.web-application-type=none")
class MarketApplicationContextTest {
    @Test
    void applicationContextLoadsWithProductionDependencyWiring() {}
}
