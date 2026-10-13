package com.campusconnect.domain.election;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class ElectionDocumentsTest extends MongoDocumentTest {

    @Autowired
    ClubElectionRepository elections;

    @Autowired
    ElectionBallotRepository ballots;

    @Test
    void electionRoundTrip() {
        ClubElection election = new ClubElection();
        election.setClub(newId());
        election.setRole("PRESIDENT");
        election.setRoleName("President");
        election.setTitle("President 2026-27");
        election.setDescription("Vote for the next president");
        ElectionCandidate asha = new ElectionCandidate();
        asha.setUser(newId());
        asha.setStatement("Ran the tech fest last year");
        asha.setVotes(12);
        ElectionCandidate bina = new ElectionCandidate();
        bina.setUser(newId());
        election.setCandidates(List.of(asha, bina));
        election.setOpensAt(at(0));
        election.setClosesAt(at(1440));
        election.setStatus(ElectionStatus.CLOSED);
        election.setEligibleCount(40);
        election.setVotesCast(12);
        election.setRunoffOf(newId());
        election.setCreatedBy(newId());
        election.setClosedAt(at(1440));
        election.setClosedBy(newId());
        election.setCancelledAt(at(1500));
        election.setCancelReason("Tie");
        elections.save(election);

        assertSameDocument(elections.findById(election.getId()).orElseThrow(), election);
        Document raw = raw("clubelections");
        assertObjectIds(raw, "_id", "club", "candidates.0.user", "candidates.1.user", "runoffOf", "createdBy",
                "closedBy");
        assertAbsent(raw, "candidates.0._id");
        assertThat(valueAt(raw, "candidates.1.votes")).isEqualTo(0);
        assertThat(raw.getString("status")).isEqualTo("CLOSED");
        JsonNode json = toJson(election);
        assertThat(json.get("candidates").get(0).get("statement").stringValue()).isEqualTo(asha.getStatement());
        assertThat(json.get("status").stringValue()).isEqualTo("CLOSED");
        assertThat(new ClubElection().getStatus()).isEqualTo(ElectionStatus.SCHEDULED);
    }

    private static ElectionBallot ballot(String election, String voter) {
        ElectionBallot ballot = new ElectionBallot();
        ballot.setElection(election);
        ballot.setVoter(voter);
        return ballot;
    }

    @Test
    void ballotRecordsOnlyThatSomeoneVoted() {
        ElectionBallot ballot = ballots.save(ballot(newId(), newId()));

        Document raw = raw("electionballots");
        assertObjectIds(raw, "_id", "election", "voter");
        assertThat(raw.keySet()).doesNotContain("createdAt", "updatedAt", "__v");
        assertThat(raw.keySet()).isSubsetOf("_id", "election", "voter", "_class");
        JsonNode json = toJson(ballot);
        assertThat(json.get("_id").stringValue()).isEqualTo(ballot.getId());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("_id", "election", "voter");
        assertThat(ballots.existsByElectionAndVoter(ballot.getElection(), ballot.getVoter())).isTrue();
    }

    @Test
    void aMemberVotesOncePerElection() {
        ElectionBallot ballot = ballots.save(ballot(newId(), newId()));

        assertThatThrownBy(() -> ballots.save(ballot(ballot.getElection(), ballot.getVoter())))
                .isInstanceOf(DuplicateKeyException.class);
        ballots.save(ballot(newId(), ballot.getVoter()));
    }
}
