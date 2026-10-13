package com.campusconnect.feature.event;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.util.Updates;
import com.campusconnect.domain.event.Event;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Loads and writes events. Every write goes through here so that it bumps {@code updatedAt} (Mongoose timestamps
 * did that on every update) and invalidates the cached events feed (the Node clearOnWrite hook on Event).
 *
 * <p>Events are never saved whole: seat and waitlist counters change concurrently with edits, so each change is a
 * {@code $set}/{@code $inc} of exactly the fields it touches, like Mongoose's dirty-path saves.
 */
@Component
public class EventStore {

    private final MongoOperations mongo;
    private final EventFeedCache feed;

    public EventStore(MongoOperations mongo, EventFeedCache feed) {
        this.mongo = mongo;
        this.feed = feed;
    }

    public static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    /** The event, or 404 "Event not found". */
    public Event find(String id) {
        Event event = findOrNull(id);
        if (event == null) {
            throw AppException.notFound("Event not found");
        }
        return event;
    }

    public Event findOrNull(String id) {
        return id != null && ObjectId.isValid(id) ? mongo.findById(id, Event.class) : null;
    }

    public Event insert(Event event) {
        Event saved = mongo.insert(event);
        feed.invalidate();
        return saved;
    }

    /** Applies the update to one event; true when it matched. */
    public boolean update(String id, Update update) {
        return update(byId(id), update);
    }

    public boolean update(Query query, Update update) {
        boolean matched = mongo.updateFirst(query, Updates.touch(update), Event.class).getMatchedCount() > 0;
        if (matched) {
            feed.invalidate();
        }
        return matched;
    }

    /** Atomic conditional update; returns the event after (or before) the change, or null when nothing matched. */
    public Event modify(Query query, Update update, boolean returnNew) {
        Event event = mongo.findAndModify(query, Updates.touch(update), FindAndModifyOptions.options().returnNew(returnNew), Event.class);
        if (event != null) {
            feed.invalidate();
        }
        return event;
    }
}
