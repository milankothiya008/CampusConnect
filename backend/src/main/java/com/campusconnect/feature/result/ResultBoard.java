package com.campusconnect.feature.result;

import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.core.util.Texts;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultRound;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.feature.result.ResultDtos.AwardView;
import com.campusconnect.feature.result.ResultDtos.Board;
import com.campusconnect.feature.result.ResultDtos.BoardMeta;
import com.campusconnect.feature.result.ResultDtos.Counts;
import com.campusconnect.feature.result.ResultDtos.LatestRound;
import com.campusconnect.feature.result.ResultDtos.ResultCard;
import com.campusconnect.feature.result.ResultDtos.RoundsSummary;
import com.campusconnect.core.web.PageMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The Results page (ResultService.listPublishedResults): one card per event with anything published, newest first.
 * {@code stage=final} keeps events with final results, {@code stage=live} those with rounds out and final results still
 * to come. The same for every viewer and read often, so each page is cached in Redis for a few seconds; every write to
 * results clears the cache (the Node {@code clearOnWrite} hook of EventResult).
 */
@Service
public class ResultBoard {

    private static final String PUBLISHED = ResultStatus.PUBLISHED.name();

    private final MongoOperations mongo;
    private final ResultViews views;

    public ResultBoard(MongoOperations mongo, ResultViews views) {
        this.mongo = mongo;
        this.views = views;
    }

    /**
     * {@code club} is an ObjectId or "", {@code search} trimmed text or "", {@code stage} "final", "live" or "" (all).
     */
    @Cacheable(cacheNames = CacheNames.RESULTS, key = "#club + '|' + #stage + '|' + #page + '|' + #limit + '|' + #search", sync = true)
    public Board list(String club, String search, String stage, int page, int limit) {
        Pagination pagination = new Pagination(page, limit);
        Document base = new Document("$or", List.of(new Document("status", PUBLISHED), new Document("rounds.status", PUBLISHED)));
        if (!club.isEmpty()) {
            base.append("club", new ObjectId(club));
        }
        if (!search.isEmpty()) {
            Query matching = Query.query(Texts.search("title", search));
            matching.fields().include("_id");
            List<ObjectId> eventIds = mongo.find(matching, Document.class, "events").stream().map(event -> event.getObjectId("_id")).toList();
            base.append("event", new Document("$in", eventIds));
        }
        Document finalOnly = new Document(base).append("status", PUBLISHED);
        Document liveOnly = new Document(base).append("status", new Document("$ne", PUBLISHED));
        Document filter = switch (stage) {
            case "final" -> finalOnly;
            case "live" -> liveOnly;
            default -> base;
        };

        Document fields = new Document("event", 1).append("status", 1).append("publishedAt", 1).append("lastPublishedAt", 1).append("awards", 1)
                .append("rounds.name", 1).append("rounds.status", 1).append("rounds.publishedAt", 1);
        Query pageQuery = new BasicQuery(filter, fields)
                .with(Sort.by(Sort.Order.desc("lastPublishedAt"), Sort.Order.desc("publishedAt")))
                .skip(pagination.skip())
                .limit(pagination.limit());
        List<EventResult> docs = mongo.find(pageQuery, EventResult.class);
        long finalCount = mongo.count(new BasicQuery(finalOnly), EventResult.class);
        long liveCount = mongo.count(new BasicQuery(liveOnly), EventResult.class);
        long total = switch (stage) {
            case "final" -> finalCount;
            case "live" -> liveCount;
            default -> finalCount + liveCount;
        };

        Map<String, Map<String, Object>> events = views.eventRefs(docs.stream().map(EventResult::getEvent).filter(Objects::nonNull).toList());
        Set<String> winnerIds = new LinkedHashSet<>();
        docs.stream().filter(doc -> doc.getStatus() == ResultStatus.PUBLISHED)
                .forEach(doc -> topThree(doc).stream().map(ResultAward::getRecipientUser).filter(Objects::nonNull).forEach(winnerIds::add));
        Map<String, Map<String, Object>> users = views.users(winnerIds);

        List<ResultCard> items = new ArrayList<>();
        for (EventResult doc : docs) {
            List<ResultRound> published = doc.getRounds().stream().filter(round -> round.getStatus() == ResultStatus.PUBLISHED).toList();
            ResultRound latest = published.stream()
                    .sorted(Comparator.comparing((ResultRound round) -> millis(round.getPublishedAt())).reversed())
                    .findFirst().orElse(null);
            boolean isFinal = doc.getStatus() == ResultStatus.PUBLISHED;
            List<AwardView> winners = new ArrayList<>();
            if (isFinal) {
                topThree(doc).forEach(award -> winners.add(ResultViews.award(award, users)));
            }
            items.add(new ResultCard(doc.getId(), events.get(doc.getEvent()), isFinal, doc.getPublishedAt(),
                    doc.getLastPublishedAt() != null ? doc.getLastPublishedAt() : doc.getPublishedAt(), winners,
                    new RoundsSummary(published.size(), latest == null ? null : new LatestRound(latest.getName(), latest.getPublishedAt()))));
        }
        PageMeta meta = pagination.meta(total);
        return new Board(items, new BoardMeta(new Counts(finalCount + liveCount, finalCount, liveCount), meta.total(), meta.page(), meta.limit(), meta.totalPages()));
    }

    private static List<ResultAward> topThree(EventResult doc) {
        return doc.getAwards().stream().sorted(ResultViews.BY_POSITION).limit(3).toList();
    }

    private static long millis(Instant value) {
        return value == null ? 0 : value.toEpochMilli();
    }
}
