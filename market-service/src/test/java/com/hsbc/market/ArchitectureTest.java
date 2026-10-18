package com.hsbc.market;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

class ArchitectureTest {
  @Test
  void enforcesControllerServiceDaoMapperDependencyDirection() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hsbc.market");

    noClasses()
        .that()
        .resideInAPackage("com.hsbc.market.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.hsbc.market.dao..",
            "com.hsbc.market.mapper..",
            "com.hsbc.market.client..",
            "com.hsbc.market.export..")
        .check(classes);

    noClasses()
        .that()
        .resideInAPackage("com.hsbc.market.service..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.hsbc.market.mapper..", "org.apache.ibatis..")
        .check(classes);

    noClasses()
        .that()
        .resideOutsideOfPackage("com.hsbc.market.dao..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.hsbc.market.mapper.query..", "com.hsbc.market.mapper.export..")
        .check(classes);

    classes()
        .that()
        .resideInAPackage("com.hsbc.market.dao..")
        .and()
        .areTopLevelClasses()
        .and()
        .doNotHaveSimpleName("package-info")
        .should()
        .beAnnotatedWith(Repository.class)
        .check(classes);

    classes()
        .that()
        .resideInAnyPackage("com.hsbc.market.mapper.query..", "com.hsbc.market.mapper.export..")
        .and()
        .areInterfaces()
        .should()
        .beAnnotatedWith(Mapper.class)
        .check(classes);
  }
}
