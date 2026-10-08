package com.binitech.elosys;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.binitech.elosys",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainIsPlainJava =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "java..");

  @ArchTest
  static final ArchRule applicationDependsOnlyOnDomain =
      classes()
          .that()
          .resideInAPackage("..application..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..application..", "..domain..", "java..", "org.slf4j..");

  @ArchTest
  static final ArchRule inboundAdaptersDoNotUseOutboundAdapters =
      noClasses()
          .that()
          .resideInAPackage("..adapters.inbound..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapters.outbound..");

  @ArchTest
  static final ArchRule adaptersReachUseCasesOnlyThroughPorts =
      noClasses()
          .that()
          .resideInAPackage("..adapters..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..application.usecases..");
}
