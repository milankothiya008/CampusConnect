package com.campusconnect.domain.club;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.audit.AuditAction;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class ClubDocumentsTest extends MongoDocumentTest {

    @Autowired
    ClubCreationRequestRepository requests;

    @Autowired
    ClubRepository clubs;

    @Autowired
    ClubMembershipRepository memberships;

    @Autowired
    ClubSubscriptionRepository subscriptions;

    // ------------------------------------------------------------------ ClubCreationRequest

    @Test
    void creationRequestRoundTrip() {
        ClubCreationRequest request = new ClubCreationRequest();
        request.setName("Robotics Club");
        request.setDescription("Robots");
        request.setPurpose("Build robots");
        request.setProposedActivities("Workshops");
        request.setReason("No robotics club yet");
        request.setAllDepartments(false);
        request.setDepartmentCodes(List.of("CE", "ME"));
        request.setCategory("TECHNOLOGY");
        request.setRequester(newId());
        request.setFoundingMembers(List.of(newId(), newId()));
        request.setProposedMentor(newId());
        request.setStatus(ClubRequestStatus.APPROVED);
        request.setVerifiedBy(newId());
        request.setVerifiedAt(at(10));
        request.setReviewComment("Good plan");
        request.setDecidedBy(newId());
        request.setDecidedAt(at(20));
        request.setRejectionReason("n/a");
        request.setClub(newId());
        ClubRequestHistoryEntry submitted = new ClubRequestHistoryEntry();
        submitted.setAction(AuditAction.CLUB_REQUEST_SUBMITTED);
        submitted.setToStatus(ClubRequestStatus.PENDING_FACULTY_REVIEW);
        submitted.setActor(request.getRequester());
        submitted.setAt(at(0));
        ClubRequestHistoryEntry verified = new ClubRequestHistoryEntry();
        verified.setAction(AuditAction.CLUB_REQUEST_VERIFIED);
        verified.setFromStatus(ClubRequestStatus.PENDING_FACULTY_REVIEW);
        verified.setToStatus(ClubRequestStatus.FACULTY_VERIFIED);
        verified.setActor(request.getVerifiedBy());
        verified.setReason("Good plan");
        verified.setAt(at(10));
        request.setHistory(List.of(submitted, verified));
        requests.save(request);

        assertSameDocument(requests.findById(request.getId()).orElseThrow(), request);
        Document raw = raw("clubcreationrequests");
        assertObjectIds(raw, "_id", "requester", "foundingMembers.0", "foundingMembers.1", "proposedMentor",
                "verifiedBy", "decidedBy", "club", "history.0.actor", "history.1.actor");
        assertAbsent(raw, "history.0._id", "history.0.fromStatus", "history.0.reason");
        assertDates(raw, "history.1.at", "verifiedAt", "createdAt");
        assertThat(valueAt(raw, "history.1.action")).isEqualTo("CLUB_REQUEST_VERIFIED");

        JsonNode json = toJson(request);
        assertThat(json.get("_id").stringValue()).isEqualTo(request.getId());
        assertThat(json.get("history").get(0).get("fromStatus").isNull()).isTrue();
        assertThat(json.get("history").get(1).get("toStatus").stringValue()).isEqualTo("FACULTY_VERIFIED");
        assertThat(json.get("foundingMembers").get(1).stringValue()).isEqualTo(request.getFoundingMembers().get(1));
        assertThat(new ClubCreationRequest().getStatus()).isEqualTo(ClubRequestStatus.PENDING_FACULTY_REVIEW);
    }

    // ------------------------------------------------------------------ Club

    private static Club club(String name) {
        Club club = new Club();
        club.setName(name);
        club.setDescription("We code");
        club.setCategory("TECHNOLOGY");
        return club;
    }

    @Test
    void clubRoundTrip() {
        Club club = club("Coding Club");
        club.setPurpose("Learn together");
        club.setAllDepartments(true);
        club.setDepartmentCodes(List.of("CE"));
        club.setLogo("https://cdn.example/logo.png");
        club.setContactEmail("coding@uni.edu");
        club.setTagline("Ship it");
        club.setCoverImage("https://cdn.example/cover.png");
        club.setWebsite("https://coding.example");
        club.setContactPhone("+919876543210");
        club.getSocialLinks().setInstagram("https://instagram.com/coding");
        club.getSocialLinks().setGithub("https://github.com/coding");
        club.setMeetingSchedule("Fridays 5 pm");
        club.setMeetingLocation("Lab 3");
        club.setMentor(newId());
        club.setPresident(newId());
        club.setRoles(List.of(
                new ClubRole("PRESIDENT", "President", "", List.of("MANAGE_CLUB"), true, 0),
                new ClubRole("TREASURER", "Treasurer", "Money", List.of("VIEW_BUDGET"), false, 30)));
        club.setStatus(ClubStatus.SUSPENDED);
        club.setStatusNote("Paused for exams");
        club.setStatusChangedAt(at(5));
        club.setPausedAt(at(5));
        club.setCreationRequest(newId());
        clubs.save(club);

        assertSameDocument(clubs.findById(club.getId()).orElseThrow(), club);
        Document raw = raw("clubs");
        assertObjectIds(raw, "_id", "mentor", "president", "creationRequest");
        assertAbsent(raw, "roles.0._id", "socialLinks.x");
        assertThat(valueAt(raw, "roles.1.order")).isEqualTo(30);
        JsonNode json = toJson(club);
        assertThat(json.get("_id").stringValue()).isEqualTo(club.getId());
        assertThat(json.get("socialLinks").get("x").isNull()).isTrue();
        assertThat(json.get("roles").get(0).get("system").booleanValue()).isTrue();
        assertThat(json.get("status").stringValue()).isEqualTo("SUSPENDED");
    }

    @Test
    void clubNamesAreUniqueAndRolesDefaultToNull() {
        Club club = clubs.save(club("Music Club"));

        assertAbsent(raw("clubs"), "roles", "mentor");
        assertThat(clubs.findById(club.getId()).orElseThrow().getRoles()).isNull();
        assertThatThrownBy(() -> clubs.save(club("Music Club"))).isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ ClubMembership

    private static ClubMembership membership(String club, String user, String role, MembershipStatus status) {
        ClubMembership membership = new ClubMembership();
        membership.setClub(club);
        membership.setUser(user);
        membership.setRole(role);
        membership.setStatus(status);
        return membership;
    }

    @Test
    void membershipRoundTrip() {
        ClubMembership membership = membership(newId(), newId(), "TREASURER", MembershipStatus.APPROVED);
        membership.setRequestMessage("I like numbers");
        membership.setDecidedBy(newId());
        membership.setDecidedAt(at(1));
        membership.setDecisionReason("Welcome");
        membership.setJoinedAt(at(1));
        memberships.save(membership);

        assertSameDocument(memberships.findById(membership.getId()).orElseThrow(), membership);
        assertObjectIds(raw("clubmemberships"), "_id", "club", "user", "decidedBy");
        JsonNode json = toJson(membership);
        assertThat(json.get("role").stringValue()).isEqualTo("TREASURER");
        assertThat(json.get("status").stringValue()).isEqualTo("APPROVED");
        assertThat(new ClubMembership().getRole()).isEqualTo("MEMBER");
    }

    @Test
    void oneMembershipPerClubAndOnePresident() {
        String club = newId();
        String student = newId();
        memberships.save(membership(club, student, "PRESIDENT", MembershipStatus.APPROVED));

        assertThatThrownBy(() -> memberships.save(membership(club, student, "MEMBER", MembershipStatus.PENDING)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> memberships.save(membership(club, newId(), "PRESIDENT", MembershipStatus.APPROVED)))
                .isInstanceOf(DuplicateKeyException.class);
        // The partial index only covers approved presidents and vice-presidents.
        memberships.save(membership(club, newId(), "PRESIDENT", MembershipStatus.PENDING));
        memberships.save(membership(club, newId(), "VICE_PRESIDENT", MembershipStatus.APPROVED));
        memberships.save(membership(club, newId(), "MEMBER", MembershipStatus.APPROVED));
        memberships.save(membership(club, newId(), "MEMBER", MembershipStatus.APPROVED));
        assertThatThrownBy(() -> memberships.save(
                        membership(club, newId(), "VICE_PRESIDENT", MembershipStatus.APPROVED)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ ClubSubscription

    @Test
    void subscriptionRoundTripAndOnePerUserAndClub() {
        ClubSubscription subscription = new ClubSubscription();
        subscription.setUser(newId());
        subscription.setClub(newId());
        subscription.setEnabled(true);
        subscriptions.save(subscription);

        assertSameDocument(subscriptions.findById(subscription.getId()).orElseThrow(), subscription);
        assertObjectIds(raw("clubsubscriptions"), "_id", "user", "club");
        assertThat(toJson(subscription).get("enabled").booleanValue()).isTrue();

        ClubSubscription again = new ClubSubscription();
        again.setUser(subscription.getUser());
        again.setClub(subscription.getClub());
        assertThatThrownBy(() -> subscriptions.save(again)).isInstanceOf(DuplicateKeyException.class);
    }
}
