package com.squaretoast.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** CLAUDE.md invariants 1–3 enforced over every production class of :backend, :backend:domain, :backend:payment-adapters. */
class ArchitectureRulesTest {

    private static final Set<String> FLOATING = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    private static JavaClasses prod;

    @BeforeAll
    static void importProduction() {
        prod = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.squaretoast");
    }

    @Test
    void everyModuleContributesProductionClasses() {
        assertThat(prod.that(resideInAPackage("com.squaretoast.domain.."))).as(":backend:domain").isNotEmpty();
        assertThat(prod.that(resideInAPackage("com.squaretoast.payment.adapter.."))).as(":backend:payment-adapters").isNotEmpty();
        assertThat(prod.contain("com.squaretoast.SquareToastApplication")).as("boot app").isTrue();
    }

    @Test
    void everyProductionPackageIsNullMarked() {
        classes().should(new ArchCondition<JavaClass>("reside in a @NullMarked package") {
            @Override public void check(JavaClass c, ConditionEvents events) {
                boolean marked = c.getPackage().isAnnotatedWith(NullMarked.class);
                events.add(new SimpleConditionEvent(c, marked,
                        c.getPackageName() + " is missing package-info.java with @NullMarked"));
            }
        }).check(prod);
    }

    @Test
    void deprecatedSpringNullAnnotationsAreForbidden() {
        noClasses().should().dependOnClassesThat().resideInAPackage("org.springframework.lang..")
                .check(prod);
    }

    @Test
    void jackson2IsConfinedToPaymentAdapters() {
        noClasses().that().resideOutsideOfPackage("com.squaretoast.payment.adapter..")
                .should().dependOnClassesThat(jackson2())
                .allowEmptyShould(false)
                .check(prod);
    }

    @Test
    void domainIsFrameworkFree() {
        noClasses().that().resideInAPackage("com.squaretoast.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "tools.jackson..", "com.fasterxml.jackson..")
                .check(prod);
    }

    @Test
    void domainDoesNotDependOnAdaptersOrApp() {
        noClasses().that().resideInAPackage("com.squaretoast.domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "com.squaretoast.domain..", "java..", "org.jspecify..")
                .check(prod);
    }

    @Test
    void noFloatingPointAnywhereInProductionCode() {
        fields().should(notHaveFloatingType()).allowEmptyShould(true).check(prod);
        codeUnits().should(notUseFloatingSignature()).allowEmptyShould(true).check(prod);
    }

    private static DescribedPredicate<JavaClass> jackson2() {
        return DescribedPredicate.describe("are Jackson 2 (com.fasterxml.jackson, excluding the shared annotations)",
                c -> c.getPackageName().startsWith("com.fasterxml.jackson")
                        && !c.getPackageName().startsWith("com.fasterxml.jackson.annotation"));
    }

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaField> notHaveFloatingType() {
        return new ArchCondition<>("not be double/float") {
            @Override public void check(com.tngtech.archunit.core.domain.JavaField f, ConditionEvents events) {
                boolean bad = FLOATING.contains(f.getRawType().getName());
                events.add(new SimpleConditionEvent(f, !bad, f.getFullName() + " uses floating point"));
            }
        };
    }

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaCodeUnit> notUseFloatingSignature() {
        return new ArchCondition<>("not take or return double/float") {
            @Override public void check(com.tngtech.archunit.core.domain.JavaCodeUnit m, ConditionEvents events) {
                boolean bad = FLOATING.contains(m.getRawReturnType().getName())
                        || m.getRawParameterTypes().stream().anyMatch(p -> FLOATING.contains(p.getName()));
                events.add(new SimpleConditionEvent(m, !bad, m.getFullName() + " uses floating point"));
            }
        };
    }
}
