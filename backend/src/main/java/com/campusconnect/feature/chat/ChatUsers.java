package com.campusconnect.feature.chat;

import com.campusconnect.core.error.AppException;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** People as chat shows them (ChatService USER_FIELDS / userView), loaded in one query per batch. */
@Component
public class ChatUsers {

    /** {@code { _id, name, avatar, accountType, departmentCode, batchCode }}. */
    public record UserView(@JsonProperty("_id") String id, String name, String avatar, AccountType accountType, String departmentCode, String batchCode) {
    }

    private static final String[] FIELDS = {"name", "avatar", "accountType", "globalRole", "departmentCode", "batchCode", "isActive"};

    private final MongoOperations mongo;

    public ChatUsers(MongoOperations mongo) {
        this.mongo = mongo;
    }

    /** The users with these ids (unknown ids are simply absent), keyed by id. */
    public Map<String, User> load(Collection<String> ids, String... extraFields) {
        List<ObjectId> unique = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).filter(ObjectId::isValid).distinct().map(ObjectId::new).toList();
        if (unique.isEmpty()) {
            return new HashMap<>();
        }
        Query query = Query.query(Criteria.where("_id").in(unique));
        for (String field : FIELDS) {
            query.fields().include(field);
        }
        for (String field : extraFields) {
            query.fields().include(field);
        }
        Map<String, User> users = new HashMap<>();
        mongo.find(query, User.class).forEach(user -> users.put(user.getId(), user));
        return users;
    }

    public User loadOne(String id) {
        return id == null ? null : load(List.of(id)).get(id);
    }

    public static UserView view(User user) {
        if (user == null) {
            return null;
        }
        return new UserView(user.getId(), user.getName(), JsValues.truthy(user.getAvatar()) ? user.getAvatar() : null, user.getAccountType(),
                JsValues.truthy(user.getDepartmentCode()) ? user.getDepartmentCode() : null, JsValues.truthy(user.getBatchCode()) ? user.getBatchCode() : null);
    }

    /** Someone who can be messaged: an active, verified account. */
    public User assertChatUser(String userId) {
        return assertChatUsers(List.of(userId == null ? "" : userId)).get(0);
    }

    /** {@link #assertChatUser} for many people at once (one query), in the order given. */
    public List<User> assertChatUsers(List<String> userIds) {
        for (String id : userIds) {
            if (!ObjectId.isValid(id)) {
                throw AppException.badRequest("Choose someone to message");
            }
        }
        Map<String, User> users = load(new LinkedHashSet<>(userIds), "isEmailVerified");
        List<User> found = new ArrayList<>(userIds.size());
        for (String id : userIds) {
            User user = users.get(id);
            if (user == null || !user.isActive() || !user.isEmailVerified()) {
                throw AppException.notFound("This account can't receive messages");
            }
            found.add(user);
        }
        return found;
    }
}
