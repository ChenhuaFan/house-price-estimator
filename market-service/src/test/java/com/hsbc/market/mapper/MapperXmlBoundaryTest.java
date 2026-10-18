package com.hsbc.market.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.mapper.export.ExportMarketQueryMapper;
import com.hsbc.market.mapper.query.ExportJobMapper;
import com.hsbc.market.mapper.query.MarketQueryMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

class MapperXmlBoundaryTest {
  private static final Path SOURCE_ROOT = Path.of("src/main/java");
  private static final Path RESOURCE_ROOT = Path.of("src/main/resources/mapper");

  @Test
  void mapperInterfacesAreSignatureOnlyAndEveryMethodHasXmlStatement() throws Exception {
    assertMapper(MarketQueryMapper.class, RESOURCE_ROOT.resolve("query/MarketQueryMapper.xml"));
    assertMapper(
        ExportMarketQueryMapper.class, RESOURCE_ROOT.resolve("export/ExportMarketQueryMapper.xml"));
    assertMapper(ExportJobMapper.class, RESOURCE_ROOT.resolve("query/ExportJobMapper.xml"));

    String javaSources =
        Files.walk(SOURCE_ROOT)
            .filter(path -> path.toString().endsWith(".java"))
            .map(MapperXmlBoundaryTest::read)
            .reduce("", (left, right) -> left + '\n' + right);
    assertThat(javaSources)
        .doesNotContain(
            "@Select(",
            "@Insert(",
            "@Update(",
            "@Delete(",
            "@SelectProvider",
            "@InsertProvider",
            "@UpdateProvider",
            "@DeleteProvider",
            "SqlProvider");
  }

  @Test
  void daoClassesAreRepositories() {
    assertThat(List.of(MarketReadDao.class, ExportJobDao.class))
        .allSatisfy(type -> assertThat(type).hasAnnotation(Repository.class));
  }

  private static void assertMapper(Class<?> mapper, Path xmlPath) throws Exception {
    assertThat(mapper).isInterface().hasAnnotation(Mapper.class);
    String xml = Files.readString(xmlPath);
    assertThat(xml).contains("namespace=\"" + mapper.getName() + "\"");
    for (Method method : mapper.getDeclaredMethods()) {
      assertThat(xml)
          .as(mapper.getSimpleName() + "." + method.getName())
          .contains("id=\"" + method.getName() + "\"");
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception error) {
      throw new IllegalStateException("cannot read " + path, error);
    }
  }
}
