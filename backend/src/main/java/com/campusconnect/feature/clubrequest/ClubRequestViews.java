package com.campusconnect.feature.clubrequest;

import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.ClubCreationRequest;
import com.campusconnect.domain.club.ClubRequestHistoryEntry;
import com.campusconnect.feature.club.DocumentViews;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Club requests as the API returns them (ClubRequestService.populateRequest): the requester, founders, mentor,
 * reviewers, the created club and every history actor populated — with one user query and one club query per list.
 */
@Component
public class ClubRequestViews {

    static final List<String> STUDENT_FIELDS = List.of("name", "email", "departmentCode", "batchCode");
    static final List<String> FACULTY_FIELDS = List.of("name", "email", "departmentCode");

    private final Refs refs;
    private final DocumentViews views;

    public ClubRequestViews(Refs refs, DocumentViews views) {
        this.refs = refs;
        this.views = views;
    }

    public Map<String, Object> of(ClubCreationRequest request) {
        return of(List.of(request)).getFirst();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> of(List<ClubCreationRequest> requests) {
        Set<String> people = new LinkedHashSet<>();
        Set<String> clubIds = new LinkedHashSet<>();
        for (ClubCreationRequest request : requests) {
            people.add(request.getRequester());
            people.addAll(request.getFoundingMembers());
            people.add(request.getProposedMentor());
            people.add(request.getVerifiedBy());
            people.add(request.getDecidedBy());
            request.getHistory().stream().map(ClubRequestHistoryEntry::getActor).forEach(people::add);
            clubIds.add(request.getClub());
        }
        people.remove(null);
        clubIds.remove(null);
        Map<String, Map<String, Object>> users = refs.users(people, "name", "email", "departmentCode", "batchCode");
        Map<String, Map<String, Object>> clubs = refs.clubs(clubIds, "name", "status");
        return requests.stream().map(request -> {
            Map<String, Object> view = views.of(request);
            view.put("requester", DocumentViews.pick(DocumentViews.row(users, request.getRequester()), STUDENT_FIELDS));
            // Populated arrays leave out documents that no longer exist.
            view.put("foundingMembers", request.getFoundingMembers().stream()
                    .map(id -> DocumentViews.pick(DocumentViews.row(users, id), STUDENT_FIELDS))
                    .filter(Objects::nonNull)
                    .toList());
            view.put("proposedMentor", DocumentViews.pick(DocumentViews.row(users, request.getProposedMentor()), FACULTY_FIELDS));
            view.put("verifiedBy", DocumentViews.pick(DocumentViews.row(users, request.getVerifiedBy()), FACULTY_FIELDS));
            view.put("decidedBy", DocumentViews.pick(DocumentViews.row(users, request.getDecidedBy()), List.of("name", "email")));
            view.put("club", DocumentViews.row(clubs, request.getClub()));
            List<Map<String, Object>> history = (List<Map<String, Object>>) view.get("history");
            for (int i = 0; i < history.size(); i++) {
                history.get(i).put("actor", DocumentViews.pick(DocumentViews.row(users, request.getHistory().get(i).getActor()), List.of("name")));
            }
            return view;
        }).toList();
    }
}
