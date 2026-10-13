package com.campusconnect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.config.JacksonConfig;
import com.campusconnect.core.config.MongoConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class of the domain mapping tests: the MongoDB slice ({@code @DataMongoTest}: mapping, repositories,
 * {@code MongoTemplate}) against a real MongoDB 8 in Docker, with automatic index creation, plus the app's own
 * {@link MongoConfig} (auditing for {@code createdAt}/{@code updatedAt}) and {@link JacksonConfig} on Boot's
 * {@code JsonMapper}. Slices skip user configuration, so both are imported explicitly.
 *
 * <p>The container is a JVM-wide singleton started in a static block (not {@code @Container}, which would stop it
 * after every test class while Spring keeps the cached context pointing at it), so all subclasses share one
 * container and one Spring context. Ryuk removes it when the JVM exits.
 *
 * <p>Collections are emptied, never dropped, before each test: the indexes are created once at startup.
 */
@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({MongoConfig.class, JacksonConfig.class})
@Testcontainers
public abstract class MongoDocumentTest {

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    static {
        MONGO.start();
    }

    private static final Comparator<Instant> MILLISECONDS =
            Comparator.nullsFirst(Comparator.comparing(instant -> instant.truncatedTo(ChronoUnit.MILLIS)));

    @Autowired
    protected MongoTemplate mongo;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void emptyCollections() {
        for (String name : mongo.getCollectionNames()) {
            mongo.getCollection(name).deleteMany(new Document());
        }
    }

    /** The first stored document of a collection, as raw BSON. */
    protected Document raw(String collection) {
        Document document = mongo.getCollection(collection).find().first();
        assertThat(document).as("a document in %s", collection).isNotNull();
        return document;
    }

    /** The value at a dotted path of a raw document ("members.0.user"). */
    protected static Object valueAt(Document document, String path) {
        Object current = document;
        for (String part : path.split("\\.")) {
            if (current instanceof Document doc) {
                current = doc.get(part);
            } else if (current instanceof List<?> list) {
                current = list.get(Integer.parseInt(part));
            } else {
                throw new AssertionError("No value at " + path + " (stopped at " + part + ")");
            }
        }
        return current;
    }

    /** Asserts that every dotted path holds an ObjectId (reference fields and sub-document ids). */
    protected static void assertObjectIds(Document document, String... paths) {
        for (String path : paths) {
            assertThat(valueAt(document, path)).as(path).isInstanceOf(ObjectId.class);
        }
    }

    /** Asserts that every dotted path holds a BSON date. */
    protected static void assertDates(Document document, String... paths) {
        for (String path : paths) {
            assertThat(valueAt(document, path)).as(path).isInstanceOf(Date.class);
        }
    }

    /**
     * Asserts that nothing is stored at these dotted paths: null fields are not written ("revision"), embedded
     * documents without an own id have none ("remindersSent.0._id").
     */
    protected static void assertAbsent(Document document, String... paths) {
        for (String path : paths) {
            int dot = path.lastIndexOf('.');
            Object parent = dot < 0 ? document : valueAt(document, path.substring(0, dot));
            assertThat(parent).as("parent of %s", path).isInstanceOf(Document.class);
            assertThat(((Document) parent).containsKey(path.substring(dot + 1))).as("%s is stored", path).isFalse();
        }
    }

    /** The document as the API would serialise it. */
    protected JsonNode toJson(Object value) {
        return json.valueToTree(value);
    }

    /**
     * Asserts that a reloaded document equals the one that was saved, field by field (nested documents and lists
     * included), comparing dates at MongoDB's millisecond precision.
     */
    protected static void assertSameDocument(Object reloaded, Object saved, String... ignoredFields) {
        assertThat(reloaded)
                .usingRecursiveComparison()
                .withComparatorForType(MILLISECONDS, Instant.class)
                .ignoringFields(ignoredFields)
                .isEqualTo(saved);
    }

    /** A date at millisecond precision, minutes after a fixed moment (so values differ and survive a round trip). */
    protected static Instant at(int minutes) {
        return Instant.parse("2026-11-02T04:30:00.123Z").plusSeconds(60L * minutes);
    }

    protected static String newId() {
        return new ObjectId().toHexString();
    }
}
