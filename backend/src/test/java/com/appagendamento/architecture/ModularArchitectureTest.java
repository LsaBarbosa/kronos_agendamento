package com.appagendamento.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModularArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.appagendamento");

  @Test
  void businessModulesMustNotHaveDependencyCycles() {
    slices().matching("com.appagendamento.(*)..").should().beFreeOfCycles().check(CLASSES);
  }

  @Test
  void domainMustNotDependOnFrameworkOrOuterLayers() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "..api..",
            "..application..",
            "..infrastructure..")
        .check(CLASSES);
  }

  @Test
  void apiMustNotAccessInfrastructureDirectly() {
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .check(CLASSES);
  }

  @Test
  void infrastructureMustRemainPrivateToItsModule() {
    List<String> modules =
        List.of(
            "tenant",
            "catalog",
            "availability",
            "appointments",
            "clients",
            "notifications",
            "subscriptions",
            "exports",
            "identityaccess",
            "clientaccess",
            "audit",
            "publicpage");
    for (String module : modules) {
      String scope = "com.appagendamento." + module + "..";
      noClasses()
          .that()
          .resideOutsideOfPackage(scope)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.appagendamento." + module + ".infrastructure..")
          .allowEmptyShould(true)
          .check(CLASSES);
    }
  }
}
