package com.campusconnect.feature.bootstrap;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.domain.venue.Venue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Accounts and data for the Gatling load test ({@code load-tests/}): {@code app.seed.load-test-users} students
 * ({@code 24celta000@domain} … with password {@link #PASSWORD}), 20 active clubs and 100 published events open for
 * registration. Off by default; refuses to run with the prod profile unless explicitly allowed, because the
 * accounts have a published password. Idempotent: existing accounts are left alone.
 */
@Slf4j
@Component
public class LoadTestSeeder {

    public static final String PASSWORD = "LoadTest#2026";
    static final int CLUBS = 20;
    static final int EVENTS_PER_CLUB = 5;

    private final MongoOperations mongo;
    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public LoadTestSeeder(MongoOperations mongo, AppProperties properties, PasswordEncoder passwordEncoder, Environment environment) {
        this.mongo = mongo;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    /** The e-mail of load-test student {@code index} (identity letters extend the 3-digit number). */
    public static String email(int index, String domain) {
        char identity = (char) ('a' + index / 1000);
        return String.format("24celt%c%03d@%s", identity, index % 1000, domain);
    }

    @Order(10)
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int users = properties.getSeed().getLoadTestUsers();
        if (users <= 0) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod")) && !properties.getSeed().isLoadTestAllowProduction()) {
            log.error("app.seed.load-test-users is set with the prod profile; refusing to create accounts with a published password");
            return;
        }
        if (mongo.exists(Query.query(Criteria.where("email").is(email(users - 1, properties.getUniversity().getDomain()))), User.class)) {
            return;
        }
        try {
            seed(Math.min(users, 26_000));
        } catch (RuntimeException e) {
            log.error("Load-test seeding failed: {}", e.getMessage());
        }
    }

    void seed(int users) {
        String domain = properties.getUniversity().getDomain();
        String hash = passwordEncoder.encode(PASSWORD);
        List<String> studentIds = new ArrayList<>();
        for (int start = 0; start < users; start += 1000) {
            List<User> batch = new ArrayList<>();
            for (int i = start; i < Math.min(users, start + 1000); i++) {
                User user = new User();
                user.setName("Load Test " + i);
                user.setEmail(email(i, domain));
                user.setPassword(hash);
                user.setAccountType(AccountType.STUDENT);
                user.setGlobalRole(GlobalRole.STUDENT);
                user.setDepartmentCode("CE");
                user.setBatchCode("24");
                user.setPhone(String.format("+9197%08d", i));
                user.setEmailVerified(true);
                batch.add(user);
            }
            mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, User.class).insert(batch).execute();
        }
        Query ids = Query.query(Criteria.where("email").regex("^24celt"));
        ids.fields().include("_id");
        mongo.find(ids, User.class).forEach(user -> studentIds.add(user.getId()));

        User mentor = new User();
        mentor.setName("Load Test Mentor");
        mentor.setEmail("loadtest.ce@" + domain);
        mentor.setPassword(hash);
        mentor.setAccountType(AccountType.FACULTY);
        mentor.setGlobalRole(GlobalRole.FACULTY);
        mentor.setDepartmentCode("CE");
        mentor.setEmailVerified(true);
        mentor = mongo.insert(mentor);

        List<Venue> venues = mongo.findAll(Venue.class);
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(14);
        int events = 0;
        for (int c = 0; c < CLUBS; c++) {
            Club club = new Club();
            club.setName("Load Test Club " + (c + 1));
            club.setDescription("A club created for load testing.");
            club.setPurpose("Load testing");
            club.setCategory("TECHNOLOGY");
            club.setAllDepartments(true);
            club.setMentor(mentor.getId());
            club.setPresident(studentIds.get(c));
            club.setStatus(ClubStatus.ACTIVE);
            club = mongo.insert(club);
            ClubMembership president = new ClubMembership();
            president.setClub(club.getId());
            president.setUser(studentIds.get(c));
            president.setRole("PRESIDENT");
            president.setStatus(MembershipStatus.APPROVED);
            president.setJoinedAt(Instant.now());
            mongo.insert(president);

            for (int e = 0; e < EVENTS_PER_CLUB; e++, events++) {
                LocalDate date = day.plusDays(events % 30);
                String start = String.format("%02d:00", 9 + (events % 8));
                String end = String.format("%02d:30", 9 + (events % 8) + 1);
                Event event = new Event();
                event.setTitle("Load Test Event " + (events + 1));
                event.setShortDescription("An event for load testing.");
                event.setDescription("Created by the load-test seeder: registrations, waitlists and feeds under load.");
                event.setCategory("TECHNOLOGY");
                event.setClub(club.getId());
                event.setVenue(venues.isEmpty() ? null : venues.get(events % venues.size()).getId());
                event.setEventDate(date.atStartOfDay().toInstant(ZoneOffset.UTC));
                event.setStartTime(start);
                event.setEndTime(end);
                event.setStartAt(CampusTime.combineDateAndTime(date.toString(), start));
                event.setEndAt(CampusTime.combineDateAndTime(date.toString(), end));
                event.setRegistrationStart(Instant.now().minus(1, ChronoUnit.DAYS));
                event.setRegistrationEnd(event.getStartAt().minus(1, ChronoUnit.DAYS));
                // Few seats on some events, so the load test also exercises the waitlist.
                event.setMaxParticipants(events % 10 == 0 ? 50 : 2000);
                event.setStatus(EventStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setOrganizer(studentIds.get(c));
                event.setCreatedBy(studentIds.get(c));
                mongo.insert(event);
            }
        }
        log.warn("Load-test data created: {} students, {} clubs, {} events (password {}). Do not keep it in production.", users, CLUBS, events, PASSWORD);
    }
}
