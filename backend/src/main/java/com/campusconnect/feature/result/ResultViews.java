package com.campusconnect.feature.result;

import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultEntry;
import com.campusconnect.domain.result.ResultRound;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.result.ResultDtos.AwardView;
import com.campusconnect.feature.result.ResultDtos.EntryView;
import com.campusconnect.feature.result.ResultDtos.ResultView;
import com.campusconnect.feature.result.ResultDtos.RoundView;
import com.campusconnect.feature.result.ResultDtos.Viewer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds the JSON of results the way ResultService.js populated it ({@code populateResult}): the event with its club,
 * award and standing recipients, and the author. Related documents are loaded in one query per type. Every list and
 * map is mutable ({@code ArrayList}/{@code LinkedHashMap}) because result pages are cached in Redis as JSON.
 */
@Component
public class ResultViews {

    /** populate("event", "title poster startAt endAt status club category registeredCount shortDescription"). */
    static final String[] EVENT_FIELDS = {"title", "poster", "startAt", "endAt", "status", "club", "category", "registeredCount", "shortDescription"};
    /** The widest user selection (awards); standings and the author use a subset of it. */
    static final String[] USER_FIELDS = {"name", "email", "departmentCode", "batchCode"};

    /** Best position first; awards without a position after the placed ones. */
    static final Comparator<ResultAward> BY_POSITION = Comparator.comparingInt(award -> award.getPosition() == null || award.getPosition() == 0 ? 99 : award.getPosition());

    private final Refs refs;

    public ResultViews(Refs refs) {
        this.refs = refs;
    }

    /** Standings are always shown best rank first; unranked rows keep their order at the end (stable). */
    static List<ResultEntry> sortEntries(List<ResultEntry> entries) {
        List<ResultEntry> sorted = new ArrayList<>(entries == null ? List.of() : entries);
        sorted.sort(Comparator.comparing(ResultEntry::getRank, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    // ---------------------------------------------------------------- events

    static Map<String, Object> clubRef(Club club) {
        if (club == null) {
            return null;
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_id", club.getId());
        ref.put("name", club.getName());
        ref.put("logo", club.getLogo());
        return ref;
    }

    /** The populated event built from an already-loaded event (its club given, or loaded here). */
    Map<String, Object> eventRef(Event event, Club club) {
        Map<String, Object> clubView = club != null ? clubRef(club) : refs.club(event.getClub(), "name", "logo");
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_id", event.getId());
        ref.put("title", event.getTitle());
        ref.put("poster", event.getPoster());
        ref.put("startAt", event.getStartAt());
        ref.put("endAt", event.getEndAt());
        ref.put("status", event.getStatus() == null ? null : event.getStatus().name());
        ref.put("club", clubView == null ? null : new LinkedHashMap<>(clubView));
        ref.put("category", event.getCategory());
        ref.put("registeredCount", event.getRegisteredCount());
        ref.put("shortDescription", event.getShortDescription());
        return ref;
    }

    /** Many populated events: one query for the events, one for their clubs. */
    Map<String, Map<String, Object>> eventRefs(Collection<String> eventIds) {
        Map<String, Map<String, Object>> events = refs.events(eventIds, EVENT_FIELDS);
        Set<String> clubIds = new LinkedHashSet<>();
        events.values().forEach(event -> {
            if (event.get("club") != null) {
                clubIds.add(String.valueOf(event.get("club")));
            }
        });
        Map<String, Map<String, Object>> clubs = refs.clubs(clubIds, "name", "logo");
        Map<String, Map<String, Object>> shaped = new HashMap<>();
        events.forEach((id, event) -> {
            Map<String, Object> copy = new LinkedHashMap<>(event);
            Map<String, Object> club = event.get("club") == null ? null : clubs.get(String.valueOf(event.get("club")));
            copy.put("club", club == null ? null : new LinkedHashMap<>(club));
            shaped.put(id, copy);
        });
        return shaped;
    }

    // ---------------------------------------------------------------- people

    Map<String, Map<String, Object>> users(Collection<String> ids) {
        return refs.users(ids, USER_FIELDS);
    }

    /** The selected fields of a loaded user (with {@code _id}), or null when there is no such user. */
    static Map<String, Object> pick(Map<String, Object> user, String... fields) {
        if (user == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("_id", user.get("_id"));
        for (String field : fields) {
            view.put(field, user.get(field));
        }
        return view;
    }

    static AwardView award(ResultAward award, Map<String, Map<String, Object>> users) {
        Map<String, Object> recipient = award.getRecipientUser() == null ? null : pick(users.get(award.getRecipientUser()), USER_FIELDS);
        return new AwardView(award.getId(), award.getTitle(), award.getPosition(), award.getRecipientName(), recipient, award.getTeamName(), award.getPrize(), award.getRecognition());
    }

    static EntryView entry(ResultEntry entry, Map<String, Map<String, Object>> users) {
        Map<String, Object> recipient = entry.getRecipientUser() == null ? null : pick(users.get(entry.getRecipientUser()), "name", "departmentCode", "batchCode");
        return new EntryView(entry.getId(), entry.getRank(), recipient, entry.getRecipientName(), entry.getTeamName(), entry.getScore(), entry.getQualified(), entry.getNote());
    }

    static RoundView round(ResultRound round, Map<String, Map<String, Object>> users) {
        List<EntryView> entries = new ArrayList<>();
        sortEntries(round.getEntries()).forEach(entry -> entries.add(entry(entry, users)));
        return new RoundView(round.getId(), round.getName(), round.getDescription(), entries, round.getStatus(), round.getPublishedAt(), round.getPublishedBy(),
                round.getCorrectedAt(), round.getCreatedBy(), round.getUpdatedBy(), round.getCreatedAt(), round.getUpdatedAt());
    }

    // ---------------------------------------------------------------- one event's results

    /**
     * Organisers (results managers and the mentor) see drafts; everyone else sees only what is published
     * (ResultService.shapeResult). {@code club} is the event's club when the caller already has it.
     */
    ResultView result(EventResult result, Event event, Club club, Viewer viewer) {
        boolean finalPublished = result.getStatus() == ResultStatus.PUBLISHED;
        boolean showFinal = finalPublished || viewer.canSeeDrafts();
        List<ResultRound> rounds = result.getRounds().stream()
                .filter(round -> viewer.canSeeDrafts() || round.getStatus() == ResultStatus.PUBLISHED)
                .toList();

        Set<String> userIds = new LinkedHashSet<>();
        result.getAwards().stream().map(ResultAward::getRecipientUser).filter(Objects::nonNull).forEach(userIds::add);
        rounds.forEach(round -> round.getEntries().stream().map(ResultEntry::getRecipientUser).filter(Objects::nonNull).forEach(userIds::add));
        if (result.getCreatedBy() != null) {
            userIds.add(result.getCreatedBy());
        }
        Map<String, Map<String, Object>> users = users(userIds);

        List<AwardView> awards = new ArrayList<>();
        if (showFinal) {
            result.getAwards().forEach(award -> awards.add(award(award, users)));
        }
        List<RoundView> roundViews = new ArrayList<>();
        rounds.forEach(round -> roundViews.add(round(round, users)));
        Map<String, Object> author = result.getCreatedBy() == null ? null : pick(users.get(result.getCreatedBy()), "name");

        return new ResultView(result.getId(), eventRef(event, club), result.getStatus(), showFinal ? result.getSummary() : "", awards, result.getPublishedAt(),
                result.getCorrectedAt(), result.getLastPublishedAt(), author, result.getUpdatedAt(), roundViews, viewer);
    }
}
