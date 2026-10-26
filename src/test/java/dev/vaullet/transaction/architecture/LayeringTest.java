package dev.vaullet.transaction.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.vaullet.common.test.ArchitectureRules;

/**
 * The layering rules, enforced by the build instead of by code review: the platform's rules from
 * {@code backend-common-test}, pointed at this codebase. A rule that is copied is a rule that gets
 * edited locally, and a boundary each service defines slightly differently is not a boundary.
 *
 * <p>It arrives with the first classes in the layers, not with the scaffold: ArchUnit fails a rule
 * that finds nothing to check, and before #1 there was nothing.
 */
@AnalyzeClasses(packages = LayeringTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    static final String BASE = "dev.vaullet.transaction";

    @ArchTest
    static final ArchRule layers_are_respected = ArchitectureRules.layersAreRespected(BASE);

    @ArchTest
    static final ArchRule controllers_do_not_reach_into_repositories =
            ArchitectureRules.controllersDoNotReachIntoRepositories();

    /**
     * JDBC, not JPA, as in the ledger: nothing outside {@code dao} touches {@code JdbcTemplate}, since
     * a stray query in a service method is a statement that escaped review.
     */
    @ArchTest
    static final ArchRule jdbc_stays_in_the_dao_layer =
            ArchitectureRules.persistenceStaysInTheDaoLayer(BASE, "org.springframework.jdbc..");

    /**
     * Capture, void and expiry will be driven by listeners and sweepers, which have no request to
     * bind. A service method that reached for {@code HttpStatus} would have to be rewritten to be
     * reachable from there; the ledger's status codes stay in {@code LedgerReservations}.
     */
    @ArchTest
    static final ArchRule the_service_layer_knows_nothing_about_http =
            ArchitectureRules.serviceLayerKnowsNothingAboutHttp(BASE);

    @ArchTest
    static final ArchRule no_field_injection = ArchitectureRules.noFieldInjection();

    @ArchTest
    static final ArchRule no_standard_streams = ArchitectureRules.noStandardStreams();

    @ArchTest
    static final ArchRule no_java_util_logging = ArchitectureRules.noJavaUtilLogging();

    @ArchTest
    static final ArchRule no_joda_or_legacy_date_api = ArchitectureRules.noLegacyDateApi();
}
