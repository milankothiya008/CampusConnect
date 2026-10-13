package com.campusconnect.feature.recruitment.application;

import static com.campusconnect.feature.recruitment.drive.Drives.invalid;

import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.recruitment.AnswerFile;
import com.campusconnect.domain.recruitment.ApplicationAnswer;
import com.campusconnect.domain.recruitment.QuestionType;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentQuestion;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.support.Js;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks answers against a role's questions, across its pages (ApplicationService.js readAnswers). Uploaded files
 * must have been issued to this student for this drive. When an application is edited, {@code keepFile} keeps an
 * already uploaded file; files that were replaced or removed are returned so they can be deleted after the save.
 */
@Component
public class AnswerReader {

    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s/$.?#].[^\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Map<QuestionType, Integer> LIMITS = Map.of(QuestionType.SHORT, 300, QuestionType.PARAGRAPH, 5000, QuestionType.LINK, 500);

    private final MediaStores media;

    public AnswerReader(MediaStores media) {
        this.media = media;
    }

    /** The answers in question order, the newly attached files and the files no longer used. */
    public record Answers(List<ApplicationAnswer> answers, List<AnswerFile> newFiles, List<AnswerFile> droppedFiles) {
    }

    public Answers read(RecruitmentDrive drive, RecruitmentPosition position, AuthUser actor, List<?> input, List<ApplicationAnswer> previous) {
        Map<String, Map<?, ?>> given = new HashMap<>();
        if (input != null) {
            for (Object item : input) {
                if (item instanceof Map<?, ?> answer) {
                    given.put(answer.get("question") == null ? (answer.containsKey("question") ? "null" : "undefined") : Js.string(answer.get("question")), answer);
                }
            }
        }
        Map<String, ApplicationAnswer> before = new HashMap<>();
        if (previous != null) {
            previous.forEach(answer -> before.put(answer.getQuestion(), answer));
        }
        List<AnswerFile> newFiles = new ArrayList<>();
        List<AnswerFile> droppedFiles = new ArrayList<>();
        List<ApplicationAnswer> answers = new ArrayList<>();

        for (RecruitmentQuestion question : DrivePhases.questionsOf(position)) {
            Map<?, ?> answer = given.getOrDefault(question.getId(), Map.of());
            ApplicationAnswer out = new ApplicationAnswer();
            out.setQuestion(question.getId());
            String label = question.getLabel();
            switch (question.getType()) {
                case SHORT, PARAGRAPH, LINK -> {
                    String text = Js.trim(Js.string(answer.get("text")));
                    int limit = LIMITS.get(question.getType());
                    if (text.length() > limit) {
                        throw invalid("\"" + label + "\" can be up to " + limit + " characters");
                    }
                    if (question.getType() == QuestionType.LINK && !text.isEmpty() && !URL_PATTERN.matcher(text).matches()) {
                        throw invalid("\"" + label + "\" needs a full link starting with https://");
                    }
                    if (question.isRequired() && text.isEmpty()) {
                        throw missing(label);
                    }
                    out.setText(text);
                }
                case SINGLE_CHOICE, MULTI_CHOICE -> {
                    List<?> raw = answer.get("choices") instanceof List<?> list ? list : List.of();
                    Set<String> picked = new LinkedHashSet<>();
                    raw.forEach(choice -> picked.add(choice == null ? "null" : Js.string(choice)));
                    if (picked.stream().anyMatch(choice -> !question.getOptions().contains(choice))) {
                        throw invalid("Choose from the options given for \"" + label + "\"");
                    }
                    if (question.getType() == QuestionType.SINGLE_CHOICE && picked.size() > 1) {
                        throw invalid("Pick one option for \"" + label + "\"");
                    }
                    if (question.isRequired() && picked.isEmpty()) {
                        throw missing(label);
                    }
                    out.setChoices(new ArrayList<>(picked));
                }
                case FILE -> {
                    ApplicationAnswer earlier = before.get(question.getId());
                    AnswerFile old = earlier == null ? null : earlier.getFile();
                    if (Js.truthy(answer.get("keepFile")) && old != null) {
                        out.setFile(old);
                    } else if (Js.truthy(answer.get("media"))) {
                        AnswerFile file = verified(answer.get("media"), drive, actor);
                        out.setFile(file);
                        newFiles.add(file);
                        if (old != null) {
                            droppedFiles.add(old);
                        }
                    } else if (old != null) {
                        droppedFiles.add(old);
                    }
                    if (question.isRequired() && out.getFile() == null) {
                        throw missing(label);
                    }
                }
            }
            answers.add(out);
        }
        return new Answers(answers, newFiles, droppedFiles);
    }

    private AnswerFile verified(Object raw, RecruitmentDrive drive, AuthUser actor) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> details.put(String.valueOf(key), value));
        }
        MediaStore.VerifiedMedia stored = media.recruitment().verifyUpload(details, drive.getId(), actor.id());
        AnswerFile file = new AnswerFile();
        file.setKind(stored.kind());
        file.setProvider(stored.provider());
        file.setKey(stored.key());
        file.setVersion(stored.version());
        file.setFormat(stored.format());
        file.setBytes(stored.bytes());
        Object name = details.get("name");
        String text = Js.truthy(name) ? Js.string(name) : "";
        text = text.length() > 200 ? text.substring(0, 200) : text;
        file.setName(text.isEmpty() ? null : text);
        return file;
    }

    private static RuntimeException missing(String label) {
        return invalid("Please answer \"" + label + "\"");
    }
}
