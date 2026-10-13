package com.campusconnect.feature.dashboard;

import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.event.Event;
import com.campusconnect.feature.event.EventRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The compact event cards of the dashboards (DashboardService eventCard + withState): exactly the selected fields,
 * the club and venue populated as the Node query did, and the registration window state. One query per collection.
 */
@Component
public class EventCards {

    /** DashboardService.eventCard. */
    static final List<String> CARD = List.of("title", "startAt", "endAt", "startTime", "endTime", "status", "registeredCount", "maxParticipants", "waitlistCount",
            "poster", "club", "venue", "category", "registrationEnd", "registrationStart", "registrationClosed", "participationMode", "minTeamSize", "maxTeamSize");

    /** Which references the cards populate. */
    public enum Populate {
        /** {@code .populate("venue", "name")}: the club stays an id. */
        VENUE,
        /** {@code .populate("club", "name logo").populate("venue", "name")}. */
        CLUB_AND_VENUE
    }

    private final MongoOperations mongo;
    private final Refs refs;

    public EventCards(MongoOperations mongo, Refs refs) {
        this.mongo = mongo;
        this.refs = refs;
    }

    /** Runs the query, selecting the card fields plus {@code extra}, and adds each event's registrationState. */
    public List<Map<String, Object>> find(Query query, Populate populate, String... extra) {
        String[] fields = Stream.concat(CARD.stream(), Arrays.stream(extra)).distinct().toArray(String[]::new);
        for (String field : fields) {
            query.fields().include(field);
        }
        List<Document> documents = mongo.find(query, Document.class, "events");
        List<Map<String, Object>> cards = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> card = Refs.shape(document, fields);
            // An event without proposed changes has revision null, not an object of nulls.
            if (card.get("revision") instanceof Map<?, ?> revision && revision.get("status") == null) {
                card.put("revision", null);
            }
            card.put("registrationState", EventRules.registrationWindowState(mongo.getConverter().read(Event.class, document)));
            cards.add(card);
        }
        if (populate == Populate.CLUB_AND_VENUE) {
            replace(cards, "club", refs.clubs(ids(cards, "club"), "name", "logo"));
        }
        replace(cards, "venue", refs.load("venues", ids(cards, "venue"), "name"));
        return cards;
    }

    private static List<String> ids(List<Map<String, Object>> cards, String field) {
        return cards.stream().map(card -> (String) card.get(field)).filter(Objects::nonNull).toList();
    }

    private static void replace(List<Map<String, Object>> cards, String field, Map<String, Map<String, Object>> rows) {
        cards.forEach(card -> card.put(field, rows.get((String) card.get(field))));
    }
}
