package com.campusconnect.feature.registration;

import static com.campusconnect.feature.event.Values.clean;
import static com.campusconnect.feature.event.Values.text;
import static com.campusconnect.feature.event.Values.truthy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import com.campusconnect.feature.event.Values;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;

/**
 * Custom questions organisers add to forms (utils/Forms.js): event registration forms here, and hackathon
 * submission forms. Files aren't collected — people share links instead.
 */
public final class Forms {

    public static final List<String> QUESTION_TYPES = List.of("SHORT", "PARAGRAPH", "SINGLE_CHOICE", "MULTI_CHOICE", "LINK", "NUMBER", "PHONE", "DATE");
    public static final List<String> CHOICE_TYPES = List.of("SINGLE_CHOICE", "MULTI_CHOICE");
    public static final int MAX_QUESTIONS = 25;
    public static final int MAX_OPTIONS = 15;
    static final Map<String, Integer> LIMITS = Map.of("SHORT", 200, "PARAGRAPH", 3000, "LINK", 500, "NUMBER", 30, "PHONE", 20, "DATE", 10);

    private static final Pattern URL = Pattern.compile("^https?://[^\\s]+\\.[^\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern PHONE = Pattern.compile("^[+\\d][\\d\\s-]{6,18}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Forms() {
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    /**
     * Validates the organiser's questions. {@code scopes} (e.g. TEAM, MEMBER) lets each question say who answers it;
     * an unknown scope becomes the last one. Existing question ids are kept so earlier answers still match.
     */
    public static List<FormQuestion> normalizeQuestions(Object questions, List<String> scopes, String what) {
        List<?> list = Values.list(questions);
        if (list == null) {
            throw invalid("The " + what + " questions must be a list");
        }
        if (list.size() > MAX_QUESTIONS) {
            throw invalid("A " + what + " can have up to " + MAX_QUESTIONS + " questions");
        }
        List<FormQuestion> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Map<String, Object> question = Values.map(list.get(index)) == null ? Map.of() : Values.map(list.get(index));
            String where = "Question " + (index + 1);
            String type = truthy(question.get("type")) ? text(question.get("type")) : "";
            if (!QUESTION_TYPES.contains(type)) {
                throw invalid(where + " has an unknown question type");
            }
            String label = clean(question.get("label"), 200);
            if (label.isEmpty()) {
                throw invalid(where + " needs a question");
            }
            List<String> options = new ArrayList<>();
            if (CHOICE_TYPES.contains(type)) {
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                List<?> given = Values.list(question.get("options"));
                if (given != null) {
                    given.stream().map(option -> clean(option, 100)).filter(option -> !option.isEmpty()).forEach(unique::add);
                }
                options.addAll(unique);
                if (options.size() < 2 || options.size() > MAX_OPTIONS) {
                    throw invalid("\"" + label + "\" needs between 2 and " + MAX_OPTIONS + " different options");
                }
            }
            FormQuestion out = new FormQuestion();
            Object id = question.get("_id");
            if (truthy(id) && ObjectId.isValid(text(id))) {
                out.setId(text(id));
            }
            out.setType(type);
            out.setLabel(label);
            out.setHelp(clean(question.get("help"), 300));
            out.setRequired(truthy(question.get("required")));
            out.setOptions(options);
            if (scopes != null) {
                Object scope = question.get("scope");
                out.setScope(scope instanceof String given && scopes.contains(given) ? given : scopes.get(scopes.size() - 1));
            }
            result.add(out);
        }
        return result;
    }

    /** Checks a person's answers to {@code questions}; returns one answer per question, in question order. */
    public static List<FormAnswer> readAnswers(List<FormQuestion> questions, Object input) {
        Map<String, Map<String, Object>> given = new HashMap<>();
        List<?> answers = Values.list(input);
        if (answers != null) {
            for (Object answer : answers) {
                Map<String, Object> map = Values.map(answer);
                given.put(map == null ? "undefined" : String.valueOf(map.get("question")), map == null ? Map.of() : map);
            }
        }
        List<FormAnswer> result = new ArrayList<>();
        for (FormQuestion question : questions) {
            Map<String, Object> answer = given.getOrDefault(question.getId(), Map.of());
            FormAnswer out = new FormAnswer(question.getId(), "", new ArrayList<>());
            if (CHOICE_TYPES.contains(question.getType())) {
                LinkedHashSet<String> picked = new LinkedHashSet<>();
                List<?> choices = Values.list(answer.get("choices"));
                if (choices != null) {
                    choices.forEach(choice -> picked.add(String.valueOf(choice)));
                }
                if (picked.stream().anyMatch(choice -> !question.getOptions().contains(choice))) {
                    throw invalid("Choose from the options given for \"" + question.getLabel() + "\"");
                }
                if ("SINGLE_CHOICE".equals(question.getType()) && picked.size() > 1) {
                    throw invalid("Pick one option for \"" + question.getLabel() + "\"");
                }
                if (question.isRequired() && picked.isEmpty()) {
                    throw invalid("Please answer \"" + question.getLabel() + "\"");
                }
                out.setChoices(new ArrayList<>(picked));
                result.add(out);
                continue;
            }
            String answerText = text(answer.get("text")).strip();
            Integer limit = LIMITS.get(question.getType());
            if (answerText.length() > (limit == null ? 200 : limit)) {
                throw invalid("\"" + question.getLabel() + "\" can be up to " + limit + " characters");
            }
            if (question.isRequired() && answerText.isEmpty()) {
                throw invalid("Please answer \"" + question.getLabel() + "\"");
            }
            if (!answerText.isEmpty()) {
                String type = question.getType();
                if ("LINK".equals(type) && !URL.matcher(answerText).matches()) {
                    throw invalid("\"" + question.getLabel() + "\" needs a full link starting with https://");
                }
                if ("NUMBER".equals(type) && !NUMBER.matcher(answerText).matches()) {
                    throw invalid("\"" + question.getLabel() + "\" must be a number");
                }
                if ("PHONE".equals(type) && !PHONE.matcher(answerText).matches()) {
                    throw invalid("\"" + question.getLabel() + "\" must be a phone number");
                }
                if ("DATE".equals(type) && !DATE.matcher(answerText).matches()) {
                    throw invalid("\"" + question.getLabel() + "\" must be a date");
                }
            }
            out.setText(answerText);
            result.add(out);
        }
        return result;
    }

    /** "Answer text" for a list or CSV cell. */
    public static String answerText(FormQuestion question, FormAnswer answer) {
        if (answer == null) {
            return "";
        }
        return CHOICE_TYPES.contains(question.getType()) ? String.join(", ", answer.getChoices()) : answer.getText();
    }
}
