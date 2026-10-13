package campusconnect;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.ws;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 5,000 concurrent students (configurable): they sign in one by one over the ramp, each keeps the live-chat
 * WebSocket open (STOMP) and browses like a real visitor — feed, event pages, clubs, unread badges, with a few
 * registering for events (seat booking and waitlists under contention) — until the end of the hold period. So all
 * of them are online together for the whole hold, and each signs in once, as students do: their sessions then
 * renew with the refresh cookie, not the password. Needs the load-test accounts: start the API with
 * {@code SEED_LOAD_TEST_USERS=5000} (see load-tests/README.md).
 *
 * <pre>
 * mvn gatling:test -DbaseUrl=https://staging.campus.example.edu -Dusers=5000 -DrampSeconds=420 -DholdSeconds=600
 * </pre>
 *
 * Pass criteria, per request type: p95 under 800 ms and p99 under 2 s for everything a signed-in student does,
 * fewer than 1 % failed requests overall, and sign-in p95 under {@code -DsignInP95Ms} (default 3 s). Sign-in has
 * its own budget because it is deliberately slow (bcrypt, ~250 ms of CPU each): how many a stack takes per second
 * depends on its hashing threads, so the ramp must fit them (load-tests/README.md).
 */
public class CampusConnectSimulation extends Simulation {

    static final String BASE_URL = System.getProperty("baseUrl", "http://localhost");
    static final String DOMAIN = System.getProperty("domain", "ddu.ac.in");
    static final int USERS = Integer.getInteger("users", 5000);
    static final Duration RAMP = Duration.ofSeconds(Long.getLong("rampSeconds", 300));
    static final Duration HOLD = Duration.ofSeconds(Long.getLong("holdSeconds", 600));
    static final String PASSWORD = System.getProperty("password", "LoadTest#2026");
    static final int SIGN_IN_P95_MS = Integer.getInteger("signInP95Ms", 3000);
    static final String SIGN_IN = "POST /api/auth/login";
    /** Everything after sign-in, each held to the browsing targets on its own. */
    /** When every student stops browsing: the end of the hold period. */
    final long endAt = System.currentTimeMillis() + RAMP.plus(HOLD).toMillis();
    static final List<String> SIGNED_IN = List.of("WS connect /ws", "CONNECTED", "GET /api/events (feed)", "GET /api/events/:id",
            "GET /api/notifications/unread-count", "GET /api/chat/unread", "GET /api/clubs", "GET /api/auth/me", "GET /api/registrations/me",
            "POST /api/events/:id/register");
    static final char NUL = 0;

    static String email(int index) {
        char identity = (char) ('a' + index / 1000);
        return String.format("24celt%c%03d@%s", identity, index % 1000, DOMAIN);
    }

    final List<Map<String, Object>> accounts = IntStream.range(0, USERS)
            .mapToObj(i -> Map.<String, Object>of("email", email(i), "password", PASSWORD))
            .toList();

    final HttpProtocolBuilder protocol = http
            .baseUrl(BASE_URL)
            .wsBaseUrl(BASE_URL.replaceFirst("^http", "ws"))
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .acceptEncodingHeader("gzip")
            .userAgentHeader("CampusConnect-LoadTest/1.0")
            .header("Origin", BASE_URL);

    static String frame(String command, String... headers) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + NUL;
    }

    final ChainBuilder signIn = exec(http(SIGN_IN)
            .post("/api/auth/login")
            .body(StringBody("{\"email\":\"#{email}\",\"password\":\"#{password}\"}"))
            .check(status().is(200), jsonPath("$.data.accessToken").saveAs("token")));

    final ChainBuilder openChat = exec(ws("WS connect /ws").connect("/ws")
                    .onConnected(exec(ws("STOMP CONNECT")
                            .sendText(session -> frame("CONNECT", "accept-version:1.2", "heart-beat:0,0", "Authorization:Bearer " + session.getString("token")))
                            .await(15).on(ws.checkTextMessage("CONNECTED").check(regex("^CONNECTED"))))))
            .exec(ws("STOMP SUBSCRIBE").sendText(frame("SUBSCRIBE", "id:events", "destination:/user/queue/events")));

    static ChainBuilder authed(String name, String path) {
        return exec(http(name).get(path).header("Authorization", "Bearer #{token}").check(status().is(200)));
    }

    final ChainBuilder browseFeed = exec(http("GET /api/events (feed)")
            .get("/api/events?page=1&limit=12")
            .header("Authorization", "Bearer #{token}")
            .check(status().is(200), jsonPath("$.data[*]._id").findRandom().optional().saveAs("eventId")));

    final ChainBuilder openEvent = exec(session -> session.contains("eventId") ? session : session.set("eventId", "000000000000000000000000"))
            .exec(http("GET /api/events/:id").get("/api/events/#{eventId}").header("Authorization", "Bearer #{token}").check(status().in(200, 404)));

    final ChainBuilder register = exec(http("POST /api/events/:id/register")
            .post("/api/events/#{eventId}/register")
            .header("Authorization", "Bearer #{token}")
            .body(StringBody("{}"))
            // 201 registered/waitlisted, 409 already registered or a clash, 400 closed: all expected answers.
            .check(status().in(200, 201, 400, 409)));

    final ScenarioBuilder students = scenario("Student session")
            .feed(listFeeder(accounts).circular())
            .exec(signIn)
            .exec(openChat)
            .exec(browseFeed)
            .during(session -> Duration.ofMillis(Math.max(0, endAt - System.currentTimeMillis()))).on(
                    randomSwitch().on(
                            percent(30.0).then(browseFeed),
                            percent(20.0).then(openEvent),
                            percent(15.0).then(authed("GET /api/notifications/unread-count", "/api/notifications/unread-count")),
                            percent(10.0).then(authed("GET /api/chat/unread", "/api/chat/unread")),
                            percent(10.0).then(authed("GET /api/clubs", "/api/clubs?page=1&limit=12")),
                            percent(8.0).then(authed("GET /api/auth/me", "/api/auth/me")),
                            percent(5.0).then(authed("GET /api/registrations/me", "/api/registrations/me")),
                            percent(2.0).then(register))
                    .pause(Duration.ofSeconds(3), Duration.ofSeconds(10)))
            .exec(ws("WS close").close());

    static List<Assertion> passCriteria() {
        List<Assertion> criteria = new ArrayList<>();
        for (String request : SIGNED_IN) {
            criteria.add(details(request).responseTime().percentile(95.0).lt(800));
            criteria.add(details(request).responseTime().percentile(99.0).lt(2000));
        }
        criteria.add(details(SIGN_IN).responseTime().percentile(95.0).lt(SIGN_IN_P95_MS));
        criteria.add(global().failedRequests().percent().lt(1.0));
        return criteria;
    }

    {
        setUp(students.injectOpen(rampUsers(USERS).during(RAMP)))
                .protocols(protocol)
                .assertions(passCriteria());
    }
}
