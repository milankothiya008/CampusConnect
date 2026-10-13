package com.campusconnect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.IndexOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;

/**
 * The indexes Spring Data creates are exactly the ones Mongoose created: same names, keys (in order), unique, sparse,
 * partial filter and TTL. On the existing database every index then already exists and automatic index creation is
 * a no-op; a different name for the same keys would stop the app from starting (IndexOptionsConflict).
 */
class IndexDefinitionsTest extends MongoDocumentTest {

    /**
     * {@code collection | name | keys | options}, generated from the Node models ({@code schema.indexes()}, unnamed
     * indexes under the driver's default name). Only the options that matter for index identity are listed.
     */
    private static final String MONGOOSE_INDEXES = """
            academicbatches | code_1 | {'code': 1} | {'unique': true}
            auditlogs | targetType_1_targetId_1_createdAt_-1 | {'targetType': 1, 'targetId': 1, 'createdAt': -1} | {}
            auditlogs | actor_1_createdAt_-1 | {'actor': 1, 'createdAt': -1} | {}
            certificates | code_1 | {'code': 1} | {'unique': true}
            certificates | event_1_user_1_kind_1 | {'event': 1, 'user': 1, 'kind': 1} | {'unique': true}
            certificates | user_1_issuedAt_-1 | {'user': 1, 'issuedAt': -1} | {}
            chatreports | status_1_createdAt_-1 | {'status': 1, 'createdAt': -1} | {}
            chatreports | message_1_reporter_1 | {'message': 1, 'reporter': 1} | {'unique': true}
            clubcreationrequests | status_1_createdAt_-1 | {'status': 1, 'createdAt': -1} | {}
            clubcreationrequests | requester_1 | {'requester': 1} | {}
            clubcreationrequests | foundingMembers_1 | {'foundingMembers': 1} | {}
            clubelections | club_1_createdAt_-1 | {'club': 1, 'createdAt': -1} | {}
            clubelections | status_1_opensAt_1 | {'status': 1, 'opensAt': 1} | {}
            clubelections | status_1_closesAt_1 | {'status': 1, 'closesAt': 1} | {}
            clubmemberships | club_1_user_1 | {'club': 1, 'user': 1} | {'unique': true}
            clubmemberships | user_1_status_1 | {'user': 1, 'status': 1} | {}
            clubmemberships | club_1_role_1_status_1 | {'club': 1, 'role': 1, 'status': 1} | {}
            clubmemberships | one_president_one_vice_president | {'club': 1, 'role': 1} \
            | {'unique': true, 'partialFilterExpression': {'role': {'$in': ['PRESIDENT', 'VICE_PRESIDENT']}, \
            'status': 'APPROVED'}}
            clubs | name_1 | {'name': 1} | {'unique': true}
            clubs | status_1 | {'status': 1} | {}
            clubs | departmentCodes_1_status_1 | {'departmentCodes': 1, 'status': 1} | {}
            clubs | mentor_1 | {'mentor': 1} | {}
            clubsubscriptions | user_1_club_1 | {'user': 1, 'club': 1} | {'unique': true}
            clubsubscriptions | club_1_enabled_1 | {'club': 1, 'enabled': 1} | {}
            conversations | directKey_1 | {'directKey': 1} \
            | {'unique': true, 'partialFilterExpression': {'directKey': {'$type': 'string'}}}
            conversations | club_1 | {'club': 1} \
            | {'unique': true, 'partialFilterExpression': {'club': {'$type': 'objectId'}}}
            conversations | members.user_1_lastMessageAt_-1 | {'members.user': 1, 'lastMessageAt': -1} | {}
            departments | code_1 | {'code': 1} | {'unique': true}
            electionballots | election_1_voter_1 | {'election': 1, 'voter': 1} | {'unique': true}
            eventfeedbacks | event_1_user_1 | {'event': 1, 'user': 1} | {'unique': true}
            eventfeedbacks | club_1_createdAt_-1 | {'club': 1, 'createdAt': -1} | {}
            eventmedias | event_1_status_1_createdAt_-1 | {'event': 1, 'status': 1, 'createdAt': -1} | {}
            eventmedias | club_1_status_1 | {'club': 1, 'status': 1} | {}
            eventmedias | event_1_uploader_1_status_1 | {'event': 1, 'uploader': 1, 'status': 1} | {}
            eventregistrations | event_1_user_1 | {'event': 1, 'user': 1} | {'unique': true}
            eventregistrations | user_1_status_1 | {'user': 1, 'status': 1} | {}
            eventregistrations | event_1_status_1 | {'event': 1, 'status': 1} | {}
            eventregistrations | event_1_status_1_waitlistedAt_1 | {'event': 1, 'status': 1, 'waitlistedAt': 1} | {}
            eventregistrations | team_1_status_1 | {'team': 1, 'status': 1} | {}
            eventregistrations | ticketCode_1 | {'ticketCode': 1} \
            | {'unique': true, 'partialFilterExpression': {'ticketCode': {'$type': 'string'}}}
            eventregistrations | event_1_checkedInAt_1 | {'event': 1, 'checkedInAt': 1} | {}
            eventresults | event_1 | {'event': 1} | {'unique': true}
            eventresults | status_1_publishedAt_-1 | {'status': 1, 'publishedAt': -1} | {}
            eventresults | club_1_status_1 | {'club': 1, 'status': 1} | {}
            eventresults | lastPublishedAt_-1 | {'lastPublishedAt': -1} | {}
            events | club_1_status_1 | {'club': 1, 'status': 1} | {}
            events | status_1_startAt_1 | {'status': 1, 'startAt': 1} | {}
            events | venue_1_startAt_1_endAt_1 | {'venue': 1, 'startAt': 1, 'endAt': 1} | {}
            events | registrationStart_1_registrationEnd_1 | {'registrationStart': 1, 'registrationEnd': 1} | {}
            events | club_1_revision.status_1 | {'club': 1, 'revision.status': 1} | {}
            feedposts | createdAt_-1 | {'createdAt': -1} | {}
            feedposts | recipients_1_createdAt_-1 | {'recipients': 1, 'createdAt': -1} | {'sparse': true}
            feedposts | club_1_createdAt_-1 | {'club': 1, 'createdAt': -1} | {}
            feedposts | type_1_createdAt_-1 | {'type': 1, 'createdAt': -1} | {}
            hackathonentries | event_1_entryKey_1 | {'event': 1, 'entryKey': 1} | {'unique': true}
            hackathonentries | event_1_problemStatement_1 | {'event': 1, 'problemStatement': 1} | {}
            hackathons | event_1 | {'event': 1} | {'unique': true}
            hackathons | judges.user_1 | {'judges.user': 1} | {}
            likes | targetType_1_target_1_user_1 | {'targetType': 1, 'target': 1, 'user': 1} | {'unique': true}
            likes | user_1_targetType_1_target_1 | {'user': 1, 'targetType': 1, 'target': 1} | {}
            likes | targetType_1_target_1_createdAt_-1 | {'targetType': 1, 'target': 1, 'createdAt': -1} | {}
            messages | conversation_1_createdAt_-1 | {'conversation': 1, 'createdAt': -1} | {}
            messages | conversation_1_sender_1_clientId_1 | {'conversation': 1, 'sender': 1, 'clientId': 1} \
            | {'unique': true, 'partialFilterExpression': {'clientId': {'$type': 'string'}}}
            notifications | user_1_createdAt_-1 | {'user': 1, 'createdAt': -1} | {}
            notifications | user_1_readAt_1 | {'user': 1, 'readAt': 1} | {}
            pushsubscriptions | endpoint_1 | {'endpoint': 1} | {'unique': true}
            pushsubscriptions | user_1 | {'user': 1} | {}
            recruitmentapplications | drive_1_applicant_1_position_1 | {'drive': 1, 'applicant': 1, 'position': 1} \
            | {'unique': true}
            recruitmentapplications | drive_1_position_1_status_1_createdAt_1 \
            | {'drive': 1, 'position': 1, 'status': 1, 'createdAt': 1} | {}
            recruitmentapplications | status_1_offerExpiresAt_1 | {'status': 1, 'offerExpiresAt': 1} | {}
            recruitmentapplications | applicant_1_createdAt_-1 | {'applicant': 1, 'createdAt': -1} | {}
            recruitmentapplications | slots.startAt_1 | {'slots.startAt': 1} | {}
            recruitmentdrives | club_1_status_1 | {'club': 1, 'status': 1} | {}
            recruitmentdrives | status_1_applicationEnd_1 | {'status': 1, 'applicationEnd': 1} | {}
            recruitmentdrives | positions.rounds.venue_1_positions.rounds.startAt_1 \
            | {'positions.rounds.venue': 1, 'positions.rounds.startAt': 1} | {}
            refreshtokens | user_1 | {'user': 1} | {}
            refreshtokens | tokenHash_1 | {'tokenHash': 1} | {'unique': true}
            refreshtokens | expiresAt_1 | {'expiresAt': 1} | {'expireAfterSeconds': 0}
            stories | expiresAt_1_club_1_createdAt_1 | {'expiresAt': 1, 'club': 1, 'createdAt': 1} | {}
            stories | club_1_expiresAt_1 | {'club': 1, 'expiresAt': 1} | {}
            stories | story_ttl_backstop | {'expiresAt': 1} | {'expireAfterSeconds': 604800}
            storyviews | story_1_user_1 | {'story': 1, 'user': 1} | {'unique': true}
            storyviews | user_1_story_1_liked_1 | {'user': 1, 'story': 1, 'liked': 1} | {}
            storyviews | story_1_viewedAt_-1 | {'story': 1, 'viewedAt': -1} | {}
            storyviews | viewedAt_1 | {'viewedAt': 1} | {'expireAfterSeconds': 691200}
            teams | event_1_nameKey_1 | {'event': 1, 'nameKey': 1} \
            | {'unique': true, 'partialFilterExpression': {'status': 'ACTIVE'}}
            teams | event_1_status_1 | {'event': 1, 'status': 1} | {}
            teams | members.user_1_members.status_1 | {'members.user': 1, 'members.status': 1} | {}
            users | email_1 | {'email': 1} | {'unique': true}
            users | globalRole_1 | {'globalRole': 1} | {}
            users | departmentCode_1 | {'departmentCode': 1} | {}
            venues | name_1 | {'name': 1} | {'unique': true}
            """;

    private record ExpectedIndex(String collection, String name, Document keys, Document options) {
    }

    private static Map<String, List<ExpectedIndex>> expectedByCollection() {
        return MONGOOSE_INDEXES.lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\\s*\\|\\s*"))
                .map(cell -> new ExpectedIndex(
                        cell[0].trim(), cell[1].trim(), Document.parse(cell[2]), Document.parse(cell[3])))
                .collect(Collectors.groupingBy(ExpectedIndex::collection, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<String, MongoPersistentEntity<?>> documentsByCollection() {
        Map<String, MongoPersistentEntity<?>> documents = new LinkedHashMap<>();
        for (MongoPersistentEntity<?> entity : mongo.getConverter().getMappingContext().getPersistentEntities()) {
            if (entity.isAnnotationPresent(org.springframework.data.mongodb.core.mapping.Document.class)) {
                documents.put(entity.getCollection(), entity);
            }
        }
        return documents;
    }

    @Test
    void everyMongooseCollectionIsMappedWithItsMongooseName() {
        Set<String> mongoose = expectedByCollection().keySet();

        assertThat(mongoose).hasSize(32); // every Node model except EmailJob (replaced by RabbitMQ)
        assertThat(documentsByCollection().keySet()).containsAll(mongoose);
    }

    @Test
    void indexesMatchTheMongooseIndexes() {
        List<String> problems = new ArrayList<>();
        expectedByCollection().forEach((collection, expected) -> {
            Map<String, Document> actual = new LinkedHashMap<>();
            for (Document index : mongo.getCollection(collection).listIndexes()) {
                if (!"_id_".equals(index.getString("name"))) {
                    actual.put(index.getString("name"), index);
                }
            }
            for (ExpectedIndex want : expected) {
                Document have = actual.remove(want.name());
                if (have == null) {
                    problems.add(collection + ": missing index " + want.name());
                    continue;
                }
                compare(collection + "." + want.name(), want, have, problems);
            }
            actual.keySet().forEach(name -> problems.add(collection + ": index not in Mongoose: " + name));
        });

        assertThat(problems).isEmpty();
    }

    private static void compare(String where, ExpectedIndex want, Document have, List<String> problems) {
        check(where, "keys", want.keys().toJson(), have.get("key", Document.class).toJson(), problems);
        check(where, "unique", want.options().getBoolean("unique", false), have.getBoolean("unique", false), problems);
        check(where, "sparse", want.options().getBoolean("sparse", false), have.getBoolean("sparse", false), problems);
        check(where, "partialFilterExpression",
                json(want.options().get("partialFilterExpression", Document.class)),
                json(have.get("partialFilterExpression", Document.class)),
                problems);
        check(where, "expireAfterSeconds",
                seconds(want.options().get("expireAfterSeconds")), seconds(have.get("expireAfterSeconds")), problems);
    }

    private static void check(String where, String what, Object want, Object have, List<String> problems) {
        if (!Objects.equals(want, have)) {
            problems.add(where + ": " + what + " is " + have + ", Mongoose has " + want);
        }
    }

    private static String json(Document document) {
        return document == null ? null : document.toJson();
    }

    private static Long seconds(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @Test
    void creatingTheIndexesOnTheDatabaseMongooseBuiltChangesNothing() {
        Map<String, MongoPersistentEntity<?>> documents = documentsByCollection();
        IndexResolver resolver = IndexResolver.create(mongo.getConverter().getMappingContext());
        List<String> problems = new ArrayList<>();

        expectedByCollection().forEach((collection, expected) -> {
            // The collection as the Node app left it: Mongoose's indexes under the driver's names.
            String copy = "mongoose_" + collection;
            mongo.dropCollection(copy);
            for (ExpectedIndex index : expected) {
                mongo.getCollection(copy).createIndex(index.keys(), mongooseOptions(index));
            }

            IndexOperations indexes = mongo.indexOps(copy, documents.get(collection).getType());
            for (IndexDefinition definition : resolver.resolveIndexFor(documents.get(collection).getType())) {
                try {
                    indexes.createIndex(definition);
                } catch (RuntimeException e) {
                    problems.add(collection + ": " + definition.getIndexKeys().toJson() + " -> " + e.getMessage());
                }
            }
            if (indexes.getIndexInfo().size() != expected.size() + 1) {
                problems.add(collection + ": Spring Data added indexes to the Mongoose ones");
            }
            mongo.dropCollection(copy);
        });

        assertThat(problems).isEmpty();
    }

    private static IndexOptions mongooseOptions(ExpectedIndex index) {
        Document options = index.options();
        IndexOptions result = new IndexOptions()
                .name(index.name())
                .unique(options.getBoolean("unique", false))
                .sparse(options.getBoolean("sparse", false));
        if (options.containsKey("partialFilterExpression")) {
            result.partialFilterExpression(options.get("partialFilterExpression", Document.class));
        }
        if (options.containsKey("expireAfterSeconds")) {
            result.expireAfter(seconds(options.get("expireAfterSeconds")), TimeUnit.SECONDS);
        }
        return result;
    }
}
