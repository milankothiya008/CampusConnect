package com.campusconnect.support;

import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.core.security.JwtTokens;
import com.campusconnect.domain.academic.AcademicBatch;
import com.campusconnect.domain.academic.Department;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.domain.venue.Venue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Test data factory — the Java twin of tests/helpers/factory.js. Users are created straight in the database with
 * the shared password {@link #PASSWORD} and a signed access token.
 */
@Component
public class TestData {

    public static final String PASSWORD = "Passw0rd!";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final MongoOperations mongo;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokens tokens;
    private final DevInbox inbox;
    private final LocalCaches caches;
    private final StringRedisTemplate redis;
    private String passwordHash;

    public TestData(MongoOperations mongo, PasswordEncoder passwordEncoder, JwtTokens tokens, DevInbox inbox, LocalCaches caches, StringRedisTemplate redis) {
        this.mongo = mongo;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.inbox = inbox;
        this.caches = caches;
        this.redis = redis;
    }

    /** Empties every collection (indexes stay), the captured e-mails, Redis and the local caches. */
    public void reset() {
        for (String name : mongo.getCollectionNames()) {
            if (!name.startsWith("system.")) {
                mongo.getCollection(name).deleteMany(new Document());
            }
        }
        inbox.clear();
        // Every Redis database: rate limits and presence in 0, the shared caches in 1.
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll(org.springframework.data.redis.connection.RedisServerCommands.FlushOption.ASYNC);
            return null;
        });
        caches.all().values().forEach(cache -> cache.invalidateAll());
    }

    public record Venues(Venue auditorium, Venue hall) {
    }

    /** Departments CE/IT/ME, batches 23/24 and two venues (factory.seedReferenceData). */
    public Venues seedReferenceData() {
        for (String[] department : List.of(new String[] {"CE", "Computer Engineering"}, new String[] {"IT", "Information Technology"}, new String[] {"ME", "Mechanical Engineering"})) {
            Department row = new Department();
            row.setCode(department[0]);
            row.setName(department[1]);
            mongo.insert(row);
        }
        for (String code : List.of("23", "24")) {
            AcademicBatch batch = new AcademicBatch();
            batch.setCode(code);
            batch.setLabel("Batch 20" + code);
            mongo.insert(batch);
        }
        return new Venues(venue("Auditorium", "Main Campus", 300), venue("Seminar Hall A", "Academic Block", 60));
    }

    public Venue venue(String name, String location, int capacity) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setLocation(location);
        venue.setCapacity(capacity);
        return mongo.insert(venue);
    }

    private String passwordHash() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        return passwordHash;
    }

    private TestUser save(User user) {
        user.setPassword(passwordHash());
        user.setEmailVerified(true);
        User saved = mongo.insert(user);
        return new TestUser(saved.getId(), saved.getName(), saved.getEmail(), saved.getGlobalRole().name(), saved.getDepartmentCode(), saved.getBatchCode(),
                tokens.issueAccessToken(saved.getId(), saved.getGlobalRole().name()));
    }

    /** A fresh token for a user (e.g. after their role changed). */
    public String tokenFor(TestUser user) {
        User stored = mongo.findById(user.id(), User.class);
        return tokens.issueAccessToken(user.id(), stored.getGlobalRole().name());
    }

    /** Options of {@link #student(Consumer)}. */
    public static final class StudentSpec {
        String department = "CE";
        String batch = "24";
        String name;
        String phone = "default";

        public StudentSpec department(String value) {
            department = value;
            return this;
        }

        public StudentSpec batch(String value) {
            batch = value;
            return this;
        }

        public StudentSpec name(String value) {
            name = value;
            return this;
        }

        /** null: no mobile number (club members must have one). */
        public StudentSpec phone(String value) {
            phone = value;
            return this;
        }
    }

    public TestUser student() {
        return student(spec -> {
        });
    }

    public TestUser student(Consumer<StudentSpec> options) {
        StudentSpec spec = new StudentSpec();
        options.accept(spec);
        int sequence = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setName(spec.name != null ? spec.name : "Student " + sequence);
        user.setEmail(spec.batch + spec.department.toLowerCase(Locale.ROOT) + "uog" + String.format("%03d", sequence % 1000) + "@ddu.ac.in");
        user.setAccountType(AccountType.STUDENT);
        user.setGlobalRole(GlobalRole.STUDENT);
        user.setDepartmentCode(spec.department);
        user.setBatchCode(spec.batch);
        user.setPhone("default".equals(spec.phone) ? "+9198" + String.format("%08d", sequence) : spec.phone);
        return save(user);
    }

    /** Faculty e-mails allow letters only before the dot, so the sequence is encoded as letters. */
    static String letters(int n) {
        String digits = Integer.toString(n, 26);
        StringBuilder out = new StringBuilder();
        for (char digit : digits.toCharArray()) {
            out.append((char) ('a' + Character.digit(digit, 26)));
        }
        return out.toString();
    }

    public TestUser faculty() {
        return faculty("CE", null);
    }

    public TestUser faculty(String department, String name) {
        int sequence = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setName(name != null ? name : "Faculty " + sequence);
        user.setEmail("faculty" + letters(sequence) + "." + department.toLowerCase(Locale.ROOT) + "@ddu.ac.in");
        user.setAccountType(AccountType.FACULTY);
        user.setGlobalRole(GlobalRole.FACULTY);
        user.setDepartmentCode(department);
        return save(user);
    }

    public TestUser admin() {
        int sequence = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setName("University Admin");
        user.setEmail("admin" + letters(sequence) + ".ce@ddu.ac.in");
        user.setAccountType(AccountType.FACULTY);
        user.setGlobalRole(GlobalRole.ADMIN);
        user.setDepartmentCode("CE");
        return save(user);
    }

    public ClubMembership addMembership(Club club, TestUser user, String role) {
        ClubMembership membership = new ClubMembership();
        membership.setClub(club.getId());
        membership.setUser(user.id());
        membership.setRole(role);
        membership.setStatus(MembershipStatus.APPROVED);
        membership.setJoinedAt(Instant.now());
        return mongo.insert(membership);
    }

    public ClubMembership addMembership(Club club, TestUser user) {
        return addMembership(club, user, "MEMBER");
    }

    /** An active club created directly in the database, for tests that start after the club lifecycle. */
    public Club activeClub(TestUser mentor, TestUser president) {
        return activeClub("Club " + SEQUENCE.incrementAndGet(), mentor, president);
    }

    public Club activeClub(String name, TestUser mentor, TestUser president) {
        Club club = new Club();
        club.setName(name);
        club.setDescription("A club created for automated tests.");
        club.setPurpose("Testing");
        club.setCategory("TECHNOLOGY");
        club.setDepartmentCodes(new java.util.ArrayList<>(List.of("CE")));
        club.setMentor(mentor.id());
        club.setPresident(president.id());
        club.setStatus(ClubStatus.ACTIVE);
        Club saved = mongo.insert(club);
        addMembership(saved, president, "PRESIDENT");
        return saved;
    }

    /** YYYY-MM-DD for a date {@code days} from now. */
    public static String futureDate(int days) {
        return LocalDate.ofInstant(Instant.now().plus(days, ChronoUnit.DAYS), ZoneOffset.UTC).toString();
    }

    /** The draft event body of factory.eventPayload, with overrides. */
    public static Map<String, Object> eventPayload(Club club, Venue venue, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("club", club.getId());
        body.put("title", "Hack Night");
        body.put("shortDescription", "An evening of building things together.");
        body.put("description", "Teams build projects over one evening and demo them at the end.");
        body.put("category", "TECHNOLOGY");
        body.put("venue", venue.getId());
        body.put("eventDate", futureDate(10));
        body.put("startTime", "10:00");
        body.put("endTime", "13:00");
        body.put("registrationEnd", Instant.now().plus(8, ChronoUnit.DAYS).toString());
        body.put("maxParticipants", 50);
        body.put("rules", "Bring your own laptop.");
        body.put("contact", Map.of("name", "Organiser", "email", "organiser@ddu.ac.in", "phone", "9999999999"));
        if (overrides != null) {
            body.putAll(overrides);
        }
        return body;
    }

    public static Map<String, Object> eventPayload(Club club, Venue venue) {
        return eventPayload(club, venue, null);
    }

    // ---------------------------------------------------------------- captured e-mails

    public List<DevInbox.Entry> mailsTo(String email) {
        return inbox.list(email);
    }

    /** The most recent e-mail to this address, or null. */
    public DevInbox.Entry lastMailTo(String email) {
        List<DevInbox.Entry> mails = inbox.list(email);
        return mails.isEmpty() ? null : mails.get(0);
    }

    public int mailCount() {
        return inbox.list(null).size();
    }
}
