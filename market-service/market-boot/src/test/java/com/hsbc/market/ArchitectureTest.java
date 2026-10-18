package com.hsbc.market;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void coreDomainAndUseCasesRemainIndependentOfFrameworkAndAdapterLayers() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hsbc.market");

    noClasses()
        .that()
        .resideInAnyPackage("com.hsbc.market.domain..", "com.hsbc.market.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.hsbc.market.adapter..",
            "com.hsbc.market.api..",
            "com.hsbc.market.config..",
            "com.hsbc.market.contract..",
            "org.springframework..",
            "org.apache.ibatis..")
        .check(classes);
  }
}
