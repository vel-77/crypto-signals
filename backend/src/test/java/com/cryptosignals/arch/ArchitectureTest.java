package com.cryptosignals.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable enforcement of the modular architecture.
 *
 * <p>"The analysis engine must be testable without connecting to a real exchange" is easy to claim
 * and easy to lose: one convenient import of a repository into the engine and the claim quietly
 * stops being true. These rules fail the build at exactly that moment, so the boundaries stay real
 * rather than aspirational.
 */
@AnalyzeClasses(packages = "com.cryptosignals",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_self_contained = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..analysis..", "..exchange..", "..persistence..", "..api..",
                    "..marketdata..", "..notifications..", "..backtest..", "..config..")
            .because("the domain model is the vocabulary everything else is written in, "
                    + "so it must not point back at any of it");

    @ArchTest
    static final ArchRule analysis_does_not_know_about_infrastructure = noClasses()
            .that().resideInAPackage("..analysis..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..exchange..", "..persistence..", "..api..", "..marketdata..",
                    "..notifications..", "..backtest..", "..config..")
            .because("the detection engine must be exercisable from a plain unit test with a list "
                    + "of candles, with no database, no exchange and no Spring context");

    @ArchTest
    static final ArchRule analysis_is_free_of_frameworks = noClasses()
            .that().resideInAnyPackage("..analysis..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .because("framework-free core code stays fast to test and portable between runtimes");

    @ArchTest
    static final ArchRule exchange_layer_is_isolated = noClasses()
            .that().resideInAPackage("..exchange..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..analysis..", "..persistence..", "..api..", "..marketdata..", "..backtest..")
            .because("an exchange client only translates a venue's wire format into domain candles; "
                    + "adding a second venue must not require touching anything else");

    @ArchTest
    static final ArchRule persistence_does_not_reach_upwards = noClasses()
            .that().resideInAPackage("..persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..marketdata..", "..backtest..", "..notifications..", "..exchange..")
            .because("storage is a detail the layers above choose to use, not the other way round");

    @ArchTest
    static final ArchRule no_venue_specific_code_escapes_its_package = noClasses()
            .that().resideOutsideOfPackages("..exchange.binance..", "..config..")
            .should().dependOnClassesThat().resideInAPackage("..exchange.binance..")
            .because("everything above the exchange layer must go through the ExchangeClient "
                    + "interface; only the bean wiring may name a concrete venue");

    @ArchTest
    static final ArchRule layers_are_respected = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.cryptosignals.domain..")
            .layer("Analysis").definedBy("com.cryptosignals.analysis..")
            .layer("Exchange").definedBy("com.cryptosignals.exchange..")
            .layer("Persistence").definedBy("com.cryptosignals.persistence..")
            .layer("MarketData").definedBy("com.cryptosignals.marketdata..")
            .layer("Notifications").definedBy("com.cryptosignals.notifications..")
            .layer("Backtest").definedBy("com.cryptosignals.backtest..")
            .layer("Api").definedBy("com.cryptosignals.api..")

            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("MarketData").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Backtest").mayOnlyBeAccessedByLayers("Api", "MarketData")
            .whereLayer("Notifications").mayOnlyBeAccessedByLayers("Api", "MarketData")
            .whereLayer("Persistence").mayOnlyBeAccessedByLayers(
                    "Api", "MarketData", "Backtest", "Notifications")
            .whereLayer("Analysis").mayOnlyBeAccessedByLayers(
                    "Api", "MarketData", "Backtest", "Notifications", "Persistence");

    @ArchTest
    static final ArchRule no_package_cycles = slices()
            .matching("com.cryptosignals.(*)..")
            .should().beFreeOfCycles();
}
