package dev.vaullet.transaction;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vaullet.common.test.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The service starts, migrates, and answers what a cluster asks of it before any request: the
 * health probes, the build it is, and its API document.
 */
@IntegrationTest
class ApplicationIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the readiness probe answers UP without a token")
    void ready() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("actuator/info names the build")
    void build_info() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact", is("transaction-service")));
    }

    @Test
    @DisplayName("Flyway ran, and the service's own role exists to be granted to")
    void migrated() {
        Integer roles = jdbc.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname = 'transaction_app'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(roles).isEqualTo(1);
    }

    @Test
    @DisplayName("the API document is served")
    void api_docs() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title", is("Vaullet Transactions API")));
    }
}
