package com.rescrm.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rescrm.commercialmodel.CommercialModel;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.money.Percentage;
import com.rescrm.platform.money.persistence.MoneyColumns;
import jakarta.persistence.Column;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.StreamSupport;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture rules, enforced at build time.
 *
 * <p>Doc 28 decision A15: these are build-breaking, not advisory. Containment that depends on
 * reviewer vigilance is not containment — it works until the week someone is busy.
 *
 * <p>Several rules below currently match few or no classes, because the modules they govern
 * arrive in later epics. That is intentional: the rule exists <em>before</em> the code it
 * constrains, so the first violation fails a build rather than becoming precedent. Rules that
 * can legitimately match nothing today are marked {@code allowEmptyShould(true)}.
 */
@DisplayName("Architecture rules")
class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.rescrm";
    private static final String COMMERCIAL_MODEL_PACKAGE = "com.rescrm.commercialmodel";
    private static final String POLICY_PACKAGE = COMMERCIAL_MODEL_PACKAGE + ".policy";

    /** Doc 21 section 2a, constraint 1 and decision A10. The count is the control. */
    private static final int MAX_POLICY_POINTS = 8;

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    // =================================================================================
    // Commercial-model containment — the central control (doc 28, section 3)
    // =================================================================================

    @Test
    @DisplayName("CommercialModel is referenced only inside the commercialmodel package")
    void commercial_model_is_contained() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(COMMERCIAL_MODEL_PACKAGE + "..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        COMMERCIAL_MODEL_PACKAGE + ".CommercialModel")
                .because("commercial-model behaviour must be resolved through the eight policy "
                        + "interfaces, never by branching on the enum elsewhere (doc 25 section 6, "
                        + "doc 28 section 3). If you need model-dependent behaviour, add it to a "
                        + "policy implementation.")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("The commercialmodel package does not depend on business modules")
    void commercial_model_package_stays_independent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(COMMERCIAL_MODEL_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".crm..",
                        BASE_PACKAGE + ".inventory..",
                        BASE_PACKAGE + ".deals..",
                        BASE_PACKAGE + ".paymentplans..",
                        BASE_PACKAGE + ".collections..",
                        BASE_PACKAGE + ".commissions..",
                        BASE_PACKAGE + ".reporting..")
                .because("policies are consulted by modules, not the other way round; a "
                        + "dependency back into a module would make the policy layer untestable "
                        + "in isolation")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("There are at most eight commercial-model policy points")
    void policy_points_are_capped_at_eight() {
        List<String> policies = StreamSupport.stream(productionClasses.spliterator(), false)
                .filter(javaClass -> javaClass.getPackageName().equals(POLICY_PACKAGE))
                .filter(JavaClass::isInterface)
                .filter(javaClass -> javaClass.getSimpleName().endsWith("Policy"))
                .map(JavaClass::getSimpleName)
                .sorted()
                .toList();

        // Doc 21 section 2a: "Adding a ninth is a design decision requiring justification,
        // not a routine change." The count is what stops model-branching from spreading:
        // eight named decision points are auditable, twenty are a switch statement wearing
        // interfaces. Raising this number should be as uncomfortable as it looks.
        assertThat(policies)
                .as("the commercial-model policy points are %s", policies)
                .hasSizeLessThanOrEqualTo(MAX_POLICY_POINTS);
    }

    @Test
    @DisplayName("Policy interfaces never expose the commercial model itself")
    void policies_do_not_leak_the_enum() {
        // A policy whose method returned CommercialModel would hand every caller the enum
        // the containment rule exists to withhold, and the architecture would be decorative.
        ArchRule rule = noMethods()
                .that().arePublic()
                .and().areDeclaredInClassesThat().resideInAPackage(POLICY_PACKAGE)
                .should().haveRawReturnType(CommercialModel.class)
                .because("a caller receives answers, never the model; otherwise the policy "
                        + "layer is a getter with extra steps (doc 25 section 6)")
                .allowEmptyShould(true);

        rule.check(productionClasses);

        methods().that().arePublic()
                .and().areDeclaredInClassesThat().resideInAPackage(POLICY_PACKAGE)
                .should(notAcceptTheCommercialModelEnum())
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    // =================================================================================
    // Money discipline (doc 28, decisions A16 and A17)
    // =================================================================================

    @Test
    @DisplayName("No floating point anywhere in financial packages")
    void no_floating_point_in_financial_code() {
        DescribedPredicate<JavaClass> isFloatingPoint = new DescribedPredicate<>("float or double") {
            @Override
            public boolean test(JavaClass javaClass) {
                String name = javaClass.getName();
                return "float".equals(name) || "double".equals(name)
                        || Float.class.getName().equals(name) || Double.class.getName().equals(name);
            }
        };

        ArchRule noFloatingFields = noFields()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".platform.money..",
                        BASE_PACKAGE + ".finance..")
                .should().haveRawType(isFloatingPoint)
                .because("binary floating point cannot represent decimal money exactly; "
                        + "this is the defect class the entire financial design exists to avoid")
                .allowEmptyShould(true);

        ArchRule noFloatingReturns = noMethods()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".platform.money..",
                        BASE_PACKAGE + ".finance..")
                .should().haveRawReturnType(isFloatingPoint)
                .because("a floating-point return type re-introduces rounding error at the "
                        + "call site even when the computation was exact")
                .allowEmptyShould(true);

        noFloatingFields.check(productionClasses);
        noFloatingReturns.check(productionClasses);

        methods().that().areDeclaredInClassesThat()
                .resideInAnyPackage(BASE_PACKAGE + ".platform.money..", BASE_PACKAGE + ".finance..")
                .should(notAcceptFloatingPointParameters())
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    @DisplayName("Money is used instead of raw BigDecimal in domain and service APIs")
    void money_type_is_used_instead_of_raw_bigdecimal() {
        // Adapters are exempt: a Jackson serializer and a JPA converter exist precisely to
        // translate between Money and the raw representation, so BigDecimal must appear there.
        ArchRule rule = noMethods()
                .that().arePublic()
                .and().areDeclaredInClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".finance..",
                        BASE_PACKAGE + ".deals..",
                        BASE_PACKAGE + ".paymentplans..",
                        BASE_PACKAGE + ".collections..",
                        BASE_PACKAGE + ".commissions..")
                .and().areDeclaredInClassesThat().resideOutsideOfPackages(
                        BASE_PACKAGE + "..persistence..",
                        BASE_PACKAGE + "..jackson..")
                .should().haveRawReturnType(BigDecimal.class)
                .because("raw BigDecimal in a domain API lets a caller bypass the rounding "
                        + "policy and the currency check; use Money (doc 28, decision A17)")
                .allowEmptyShould(true);

        rule.check(productionClasses);

        methods().that().arePublic()
                .and().areDeclaredInClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".finance..",
                        BASE_PACKAGE + ".deals..",
                        BASE_PACKAGE + ".paymentplans..",
                        BASE_PACKAGE + ".collections..",
                        BASE_PACKAGE + ".commissions..")
                .and().areDeclaredInClassesThat().resideOutsideOfPackages(
                        BASE_PACKAGE + "..persistence..",
                        BASE_PACKAGE + "..jackson..")
                .should(notAcceptRawBigDecimalParameters())
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    @DisplayName("Persistent Money and Percentage columns declare their SQL domain")
    void money_columns_declare_their_domain() {
        // The rule this encodes was learned the hard way in Epic 3. V1 created money_amount
        // and rate_percentage as PostgreSQL DOMAINs and recorded that they are "transparent
        // to JDBC, so no driver or mapping configuration depends on them". They are not:
        // DatabaseMetaData reports a domain column as Types.DISTINCT carrying the domain's
        // name, so Hibernate's schema validation rejects a mapping that does not name it —
        // and declaring precision and scale does not help, because the validator then expects
        // numeric(18,2) and still finds money_amount (Types#DISTINCT).
        //
        // It went unnoticed for three epics because no entity mapped a money column until
        // units.list_price. Epics 5 to 8 add a few dozen more, so the rule is enforced here
        // rather than remembered: the build fails now instead of the application context
        // failing on somebody's first run.
        assertThat(offendingMoneyColumns(Money.class, MoneyColumns.AMOUNT))
                .as("Money columns missing columnDefinition = MoneyColumns.AMOUNT")
                .isEmpty();

        assertThat(offendingMoneyColumns(Percentage.class, MoneyColumns.RATE))
                .as("Percentage columns missing columnDefinition = MoneyColumns.RATE")
                .isEmpty();
    }

    @Test
    @DisplayName("Legacy date and time types are not used")
    void no_legacy_date_types() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + "..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Calendar")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.sql.Date")
                .because("java.time is unambiguous about whether a value carries a time zone; "
                        + "due dates are calendar dates and must not silently acquire an instant "
                        + "(doc 22, section 9)")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    // =================================================================================
    // Module boundaries (doc 28, section 2)
    // =================================================================================

    @Test
    @DisplayName("The platform layer does not depend on business modules")
    void platform_does_not_depend_on_domain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + ".platform..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE_PACKAGE + ".identity..",
                        BASE_PACKAGE + ".crm..",
                        BASE_PACKAGE + ".inventory..",
                        BASE_PACKAGE + ".reservations..",
                        BASE_PACKAGE + ".deals..",
                        BASE_PACKAGE + ".paymentplans..",
                        BASE_PACKAGE + ".collections..",
                        BASE_PACKAGE + ".commissions..",
                        BASE_PACKAGE + ".reporting..")
                .because("platform is infrastructure shared by every module; a dependency "
                        + "downward would make it un-reusable and create a cycle")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("Modules do not reach into another module's internals")
    void modules_do_not_access_foreign_internals() {
        // Same-module access to a repository is legitimate and must not be flagged; only
        // cross-module access is a violation. The condition therefore compares the module
        // segment of the accessing class with that of the accessed class.
        ArchRule rule = classes()
                .that().resideInAPackage(BASE_PACKAGE + "..")
                .should(notAccessAnotherModulesInternals())
                .because("cross-module access goes through a published service interface; "
                        + "reaching into another module's repositories or entities couples the "
                        + "two to each other's storage and defeats the modular monolith "
                        + "(doc 28, section 2)")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("The reporting module never writes")
    void reporting_is_read_only() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + ".reporting..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Writer")
                .because("reporting reads the transactional store through its own queries; a "
                        + "write path from a reporting query is how an aggregate silently "
                        + "mutates its own source (doc 21, section 2)")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("Controllers do not contain business logic or touch repositories directly")
    void controllers_delegate() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers translate HTTP; the transaction boundary and the rules "
                        + "live in the application service (doc 28, section 8)")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    // =================================================================================
    // General hygiene
    // =================================================================================

    @Test
    @DisplayName("Production code does not print to standard output")
    void no_standard_output() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE_PACKAGE + "..")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("output that bypasses the logger has no correlation id and cannot be "
                        + "filtered or shipped")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("Financial core classes are free of framework dependencies")
    void financial_core_is_framework_free() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        BASE_PACKAGE + ".finance..",
                        BASE_PACKAGE + ".platform.money")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "com.fasterxml.jackson..")
                .because("the calculators are pure functions so every rule in doc 17 is "
                        + "unit-testable with no infrastructure; adapters live in the "
                        + ".jackson and .persistence sub-packages instead (doc 28, decision A16)")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    // =================================================================================
    // Custom conditions
    // =================================================================================

    /**
     * Persistent fields of the given type whose {@code @Column} does not name the SQL domain.
     *
     * <p>Reported as a list rather than as an ArchUnit condition so the failure message names
     * the fields and the fix in one line, which is what somebody adding the twentieth money
     * column at the end of a long day actually needs.
     */
    private static List<String> offendingMoneyColumns(Class<?> fieldType, String expected) {
        String constant = expected.equals(MoneyColumns.AMOUNT) ? "AMOUNT" : "RATE";
        return StreamSupport.stream(productionClasses.spliterator(), false)
                .flatMap(javaClass -> javaClass.getFields().stream())
                .filter(field -> field.getRawType().isEquivalentTo(fieldType))
                .filter(field -> field.isAnnotatedWith(Column.class))
                .filter(field -> !expected.equals(
                        field.getAnnotationOfType(Column.class).columnDefinition()))
                .map(field -> field.getFullName()
                        + " -> add columnDefinition = MoneyColumns." + constant)
                .sorted()
                .toList();
    }

    private static ArchCondition<JavaMethod> notAcceptTheCommercialModelEnum() {
        return new ArchCondition<>("not accept a CommercialModel parameter") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass parameter : method.getRawParameterTypes()) {
                    if (CommercialModel.class.getName().equals(parameter.getName())) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " accepts a CommercialModel; policies "
                                        + "take the opaque model code so no caller has to hold "
                                        + "the enum"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notAcceptFloatingPointParameters() {
        return new ArchCondition<>("not accept float or double parameters") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass parameter : method.getRawParameterTypes()) {
                    String name = parameter.getName();
                    boolean floating = "float".equals(name) || "double".equals(name)
                            || Float.class.getName().equals(name)
                            || Double.class.getName().equals(name);
                    if (floating) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " accepts a floating-point parameter ("
                                        + name + "); money and rates must use Money or BigDecimal"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notAcceptRawBigDecimalParameters() {
        return new ArchCondition<>("not accept raw BigDecimal parameters") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass parameter : method.getRawParameterTypes()) {
                    if (BigDecimal.class.getName().equals(parameter.getName())) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " accepts a raw BigDecimal; use Money so "
                                        + "the rounding policy and currency check cannot be bypassed"));
                    }
                }
            }
        };
    }

    /**
     * Flags access from one module into another module's {@code .repository} or {@code .entity}
     * package. Access within the owning module, and access to anything under {@code platform},
     * is legitimate and is not reported.
     */
    private static ArchCondition<JavaClass> notAccessAnotherModulesInternals() {
        return new ArchCondition<>("not access another module's repository or entity packages") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source.getPackageName());
                source.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (!targetPackage.startsWith(BASE_PACKAGE)) {
                        return;
                    }
                    boolean internal = targetPackage.endsWith(".repository")
                            || targetPackage.endsWith(".entity");
                    if (!internal) {
                        return;
                    }
                    String targetModule = moduleOf(targetPackage);
                    if (targetModule.equals("platform") || targetModule.equals(sourceModule)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(source,
                            source.getName() + " (module '" + sourceModule + "') accesses "
                                    + dependency.getTargetClass().getName()
                                    + " (module '" + targetModule + "'); use that module's "
                                    + "published service interface instead"));
                });
            }
        };
    }

    /** The module segment of a package name: the part directly after {@code com.rescrm}. */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(BASE_PACKAGE)) {
            return "";
        }
        String remainder = packageName.substring(BASE_PACKAGE.length());
        if (remainder.startsWith(".")) {
            remainder = remainder.substring(1);
        }
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
