package com.campusconnect.feature.hackathon;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.feature.hackathon.HackathonDtos.DraftBody;
import com.campusconnect.feature.hackathon.HackathonDtos.HackathonView;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgeBody;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgingPanel;
import com.campusconnect.feature.hackathon.HackathonDtos.Leaderboard;
import com.campusconnect.feature.hackathon.HackathonDtos.ProblemBody;
import com.campusconnect.feature.hackathon.HackathonDtos.ProblemChoice;
import com.campusconnect.feature.hackathon.HackathonDtos.ProjectBody;
import com.campusconnect.feature.hackathon.HackathonDtos.RepoBody;
import com.campusconnect.feature.hackathon.HackathonDtos.ScoreBody;
import com.campusconnect.feature.hackathon.HackathonDtos.SettingsBody;
import com.campusconnect.feature.result.PathIds;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hackathon mode of HACKATHON events: the {@code /:id/hackathon} routes of routes/EventRoutes.js (signed in and verified). */
@RestController
@RequestMapping("/api/events/{id}/hackathon")
public class HackathonController {

    private final HackathonService hackathons;
    private final HackathonEntries entries;
    private final HackathonJudging judging;

    public HackathonController(HackathonService hackathons, HackathonEntries entries, HackathonJudging judging) {
        this.hackathons = hackathons;
        this.entries = entries;
        this.judging = judging;
    }

    @GetMapping
    public ApiResponse<HackathonView> get(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Hackathon fetched", hackathons.getHackathon(actor, PathIds.require("id", id)));
    }

    @PutMapping
    public ApiResponse<HackathonView> updateSettings(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody(required = false) SettingsBody body) {
        return ApiResponse.ok("Hackathon settings saved", hackathons.updateSettings(actor, PathIds.require("id", id), body));
    }

    @PostMapping("/problems")
    public ApiResponse<HackathonView> addProblem(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) ProblemBody body) {
        return ApiResponse.ok("Problem statement added", hackathons.addProblem(actor, PathIds.require("id", id), body));
    }

    @PutMapping("/problems/{problemId}")
    public ApiResponse<HackathonView> updateProblem(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String problemId,
            @RequestBody(required = false) ProblemBody body) {
        String[] valid = PathIds.require("id", id, "problemId", problemId);
        return ApiResponse.ok("Problem statement updated", hackathons.updateProblem(actor, valid[0], valid[1], body));
    }

    @DeleteMapping("/problems/{problemId}")
    public ApiResponse<HackathonView> deleteProblem(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String problemId) {
        String[] valid = PathIds.require("id", id, "problemId", problemId);
        return ApiResponse.ok("Problem statement removed", hackathons.deleteProblem(actor, valid[0], valid[1]));
    }

    @PostMapping("/judges")
    public ApiResponse<HackathonView> addJudge(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody JudgeBody body) {
        return ApiResponse.ok("Judge added", hackathons.addJudge(actor, PathIds.require("id", id), body.userId().toLowerCase(Locale.ROOT)));
    }

    @DeleteMapping("/judges/{userId}")
    public ApiResponse<HackathonView> removeJudge(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String userId) {
        String[] valid = PathIds.require("id", id, "userId", userId);
        return ApiResponse.ok("Judge removed", hackathons.removeJudge(actor, valid[0], valid[1]));
    }

    @PutMapping("/entry/problem")
    public ApiResponse<HackathonView> chooseProblem(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody ProblemChoice body) {
        return ApiResponse.ok("Problem statement chosen", entries.chooseProblem(actor, PathIds.require("id", id), body.problemId().toLowerCase(Locale.ROOT)));
    }

    @PutMapping("/entry/repo")
    public ApiResponse<HackathonView> submitRepo(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody RepoBody body) {
        return ApiResponse.ok("Repository saved", entries.submitRepo(actor, PathIds.require("id", id), body));
    }

    @PutMapping("/entry/project")
    public ApiResponse<HackathonView> submitProject(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @RequestBody(required = false) ProjectBody body) {
        return ApiResponse.ok("Project submitted", entries.submitProject(actor, PathIds.require("id", id), body));
    }

    @GetMapping("/judging")
    public ApiResponse<JudgingPanel> judging(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Judging panel fetched", judging.listForJudging(actor, PathIds.require("id", id)));
    }

    @PutMapping("/judging/{entryId}")
    public ApiResponse<JudgingPanel> score(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String entryId, @Valid @RequestBody ScoreBody body) {
        String[] valid = PathIds.require("id", id, "entryId", entryId);
        return ApiResponse.ok("Score saved", judging.scoreEntry(actor, valid[0], valid[1], body));
    }

    @GetMapping("/leaderboard")
    public ApiResponse<Leaderboard> leaderboard(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Leaderboard fetched", judging.getLeaderboard(actor, PathIds.require("id", id)));
    }

    @PostMapping("/results")
    public ApiResponse<Leaderboard> draftResults(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody(required = false) DraftBody body) {
        return ApiResponse.ok("Results draft prepared — the president can review and publish it", judging.draftResults(actor, PathIds.require("id", id), body));
    }
}
