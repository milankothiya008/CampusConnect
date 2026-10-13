package com.campusconnect.feature.bootstrap;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.security.PasswordHasher;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Start-up data (scripts/seed.js + AdminService.bootstrapAdminIfNeeded): departments, the last six batches and
 * the default venues (only what is missing — upserts, safe when several replicas start together), then the first
 * university admin from BOOTSTRAP_ADMIN_* when no admin exists yet.
 */
@Slf4j
@Component
public class StartupSeeder {

    static final List<Map<String, String>> DEPARTMENTS = List.of(
            Map.of("code", "CE", "name", "Computer Engineering"),
            Map.of("code", "IT", "name", "Information Technology"),
            Map.of("code", "EC", "name", "Electronics and Communication"),
            Map.of("code", "ME", "name", "Mechanical Engineering"),
            Map.of("code", "EE", "name", "Electrical Engineering"),
            Map.of("code", "CH", "name", "Chemical Engineering"),
            Map.of("code", "CL", "name", "Civil Engineering"));

    record VenueSeed(String name, String location, int capacity, String type, List<String> departmentCodes) {
    }

    static final List<VenueSeed> VENUES = List.of(
            new VenueSeed("Auditorium", "Main Campus", 400, "AUDITORIUM", List.of()),
            new VenueSeed("Seminar Hall A", "Academic Block", 120, "HALL", List.of()),
            new VenueSeed("Seminar Hall B", "Academic Block", 80, "HALL", List.of()),
            // Labs belong to departments: only their departments' events can book them.
            new VenueSeed("CE Software Lab", "CE Block, 2nd floor", 60, "LAB", List.of("CE")),
            new VenueSeed("IT Networks Lab", "IT Block, 1st floor", 50, "LAB", List.of("IT")),
            new VenueSeed("Innovation Lab", "CE Block, ground floor", 40, "LAB", List.of("CE", "IT")),
            new VenueSeed("EC Electronics Lab", "EC Block, 1st floor", 45, "LAB", List.of("EC")));

    private final MongoOperations mongo;
    private final AppProperties properties;
    private final PasswordHasher passwords;
    private final AuthUserService authUsers;

    public StartupSeeder(MongoOperations mongo, AppProperties properties, PasswordHasher passwords, AuthUserService authUsers) {
        this.mongo = mongo;
        this.properties = properties;
        this.passwords = passwords;
        this.authUsers = authUsers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            if (properties.getSeed().isReferenceData()) {
                seedReferenceData();
            }
            bootstrapAdminIfNeeded();
        } catch (RuntimeException e) {
            log.error("Start-up seeding failed: {}", e.getMessage());
        }
    }

    public void seedReferenceData() {
        Instant now = Instant.now();
        for (Map<String, String> department : DEPARTMENTS) {
            upsert("departments", "code", department.get("code"), new Update()
                    .set("name", department.get("name")).set("isActive", true).set("updatedAt", now).setOnInsert("createdAt", now));
        }
        int year = Year.now().getValue() % 100;
        for (int offset = 0; offset < 6; offset++) {
            String code = String.format(Locale.ROOT, "%02d", (year - offset + 100) % 100);
            upsert("academicbatches", "code", code, new Update()
                    .set("label", "Batch 20" + code).set("isActive", true).set("updatedAt", now).setOnInsert("createdAt", now));
        }
        for (VenueSeed venue : VENUES) {
            upsert("venues", "name", venue.name(), new Update()
                    .setOnInsert("location", venue.location())
                    .setOnInsert("capacity", venue.capacity())
                    .setOnInsert("type", venue.type())
                    .setOnInsert("departmentCodes", venue.departmentCodes())
                    .setOnInsert("status", "ACTIVE")
                    .setOnInsert("facilities", List.of())
                    .setOnInsert("createdAt", now)
                    .setOnInsert("updatedAt", now));
        }
    }

    private void upsert(String collection, String key, String value, Update update) {
        try {
            mongo.upsert(Query.query(Criteria.where(key).is(value)), update, collection);
        } catch (DuplicateKeyException ignored) {
            // Another replica inserted it at the same moment.
        }
    }

    public void bootstrapAdminIfNeeded() {
        AppProperties.BootstrapAdmin admin = properties.getBootstrapAdmin();
        if (admin.getEmail().isBlank() || admin.getPassword().isBlank()) {
            return;
        }
        if (mongo.exists(Query.query(Criteria.where("globalRole").is(GlobalRole.ADMIN.name())), User.class)) {
            return;
        }
        String email = admin.getEmail().trim().toLowerCase(Locale.ROOT);
        Document existing = mongo.findOne(Query.query(Criteria.where("email").is(email)), Document.class, "users");
        if (existing != null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(existing.getObjectId("_id"))), new Update()
                    .set("globalRole", GlobalRole.ADMIN.name())
                    .set("accountType", AccountType.FACULTY.name())
                    .set("isEmailVerified", true)
                    .set("updatedAt", Instant.now()), User.class);
            authUsers.evict(existing.getObjectId("_id").toHexString());
            log.info("Existing user promoted to university admin");
            return;
        }
        User user = new User();
        user.setName(admin.getName());
        user.setEmail(email);
        user.setPassword(passwords.hash(admin.getPassword()));
        user.setAccountType(AccountType.FACULTY);
        user.setGlobalRole(GlobalRole.ADMIN);
        user.setEmailVerified(true);
        try {
            mongo.insert(user);
            log.info("Bootstrap university admin created");
        } catch (DuplicateKeyException ignored) {
            // Another replica created it at the same moment.
        }
    }
}
