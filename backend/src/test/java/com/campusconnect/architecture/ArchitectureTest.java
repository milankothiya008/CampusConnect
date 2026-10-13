package com.campusconnect.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/** Layering rules of docs/CONVENTIONS.md, checked on every build. */
@AnalyzeClasses(packages = "com.campusconnect", importOptions = ArchitectureTest.ProductionCode.class)
class ArchitectureTest {

    /** Production classes only (also with a custom build folder such as target-x/test-classes). */
    static final class ProductionCode implements ImportOption {
        @Override
        public boolean includes(com.tngtech.archunit.core.importer.Location location) {
            return !location.contains("/test-classes/") && !location.contains("/test/");
        }
    }

    /** core is the foundation: it never reaches up into a feature. */
    @ArchTest
    static final ArchRule coreDoesNotDependOnFeatures = noClasses().that().resideInAPackage("com.campusconnect.core..")
            .should().dependOnClassesThat().resideInAPackage("com.campusconnect.feature..");

    /** Documents and repositories know nothing about the web or the services built on them. */
    @ArchTest
    static final ArchRule domainIsSelfContained = noClasses().that().resideInAPackage("com.campusconnect.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.campusconnect.core..", "com.campusconnect.feature..");

    /** Controllers translate HTTP and delegate: data access lives in services. */
    @ArchTest
    static final ArchRule controllersDoNotTouchTheDatabase = noClasses().that().areAnnotatedWith(RestController.class).or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .orShould().dependOnClassesThat().areAssignableTo(MongoOperations.class);

    /** Constructor injection only (immutable, testable beans). */
    @ArchTest
    static final ArchRule noFieldInjection = noFields().should().beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule loggingGoesThroughSlf4j = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
