package com.campusconnect.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class of the API integration tests: the whole application on a random port, MongoDB and Redis in Docker,
 * every collection emptied before each test (like tests/helpers/testDb.clear). All subclasses share one Spring
 * context and one set of containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, TestData.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestData data;

    @Autowired
    protected MongoOperations mongo;

    @Autowired
    protected JsonMapper json;

    @LocalServerPort
    protected int port;

    /** Anonymous API client; {@code api.as(user)} signs requests in as that user. */
    protected Api api;

    @BeforeEach
    void resetState() {
        data.reset();
        api = new Api(mvc, json);
    }
}
