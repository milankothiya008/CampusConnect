package com.campusconnect.feature.hackathon;

import static com.campusconnect.feature.hackathon.JsValues.get;
import static com.campusconnect.feature.hackathon.JsValues.string;
import static com.campusconnect.feature.hackathon.JsValues.textOrEmpty;
import static com.campusconnect.feature.hackathon.JsValues.trim;
import static com.campusconnect.feature.hackathon.JsValues.truthy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;

/**
 * The organisers' extra questions on the hackathon's final submission and the teams' answers (utils/Forms.js
 * normalizeQuestions / readAnswers, same rules and messages). Files aren't collected here — teams share links.
 */
final class SubmissionForms {

    static final List<String> QUESTION_TYPES = List.of("SHORT", "PARAGRAPH", "SINGLE_CHOICE", "MULTI_CHOICE", "LINK", "NUMBER", "PHONE", "DATE");
    private static final List<String> CHOICE_TYPES = List.of("SINGLE_CHOICE", "MULTI_CHOICE");
    private static final int MAX_QUESTIONS = 25;
    private static final int MAX_OPTIONS = 15;
    private static final Map<String, Integer> LIMITS = Map.of("SHORT", 200, "PARAGRAPH", 3000, "LINK", 500, "NUMBER", 30, "PHONE", 20, "DATE", 10);
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s]+\\.[^\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern PHONE = Pattern.compile("^[+\\d][\\d\\s-]{6,18}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private SubmissionForms() {
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message);
    }

    /** {@code String(value ?? "").trim().slice(0, max)}. */
    private static String clean(Object value, int max) {
        return JsValues.cut(trim(textOrEmpty(value)), max);
    }

    /** Validates the organiser's questions ({@code what}: "submission form"). */
    static List<FormQuestion> normalizeQuestions(Object questions, String what) {
        if (!(questions instanceof List<?> list)) {
            throw invalid("The " + what + " questions must be a list");
        }
        if (list.size() > MAX_QUESTIONS) {
            throw invalid("A " + what + " can have up to " + MAX_QUESTIONS + " questions");
        }
        List<FormQuestion> normalized = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object question = list.get(index);
            String where = "Question " + (index + 1);
            String type = JsValues.text(get(question, "type"));
            if (!QUESTION_TYPES.contains(type)) {
                throw invalid(where + " has an unknown question type");
            }
            String label = clean(get(question, "label"), 200);
            if (label.isEmpty()) {
                throw invalid(where + " needs a question");
            }
            List<String> options = new ArrayList<>();
            if (CHOICE_TYPES.contains(type)) {
                Set<String> unique = new LinkedHashSet<>();
                if (get(question, "options") instanceof List<?> given) {
                    given.forEach(option -> {
                        String cleaned = clean(option, 100);
                        if (!cleaned.isEmpty()) {
                            unique.add(cleaned);
                        }
                    });
                }
                options.addAll(unique);
                if (options.size() < 2 || options.size() > MAX_OPTIONS) {
                    throw invalid("\"" + label + "\" needs between 2 and " + MAX_OPTIONS + " different options");
                }
            }
            FormQuestion out = new FormQuestion();
            Object id = get(question, "_id");
            if (truthy(id) && ObjectId.isValid(string(id))) {
                out.setId(string(id).toLowerCase(Locale.ROOT));
            }
            out.setType(type);
            out.setLabel(label);
            out.setHelp(clean(get(question, "help"), 300));
            out.setRequired(truthy(get(question, "required")));
            out.setOptions(options);
            normalized.add(out);
        }
        return normalized;
    }

    /** Checks a team's answers to {@code questions}; returns one answer per question, in question order. */
    static List<FormAnswer> readAnswers(List<FormQuestion> questions, Object input) {
        Map<String, Object> given = new HashMap<>();
        if (input instanceof List<?> list) {
            list.forEach(answer -> given.put(string(get(answer, "question")), answer));
        }
        List<FormAnswer> answers = new ArrayList<>();
        for (FormQuestion question : questions == null ? List.<FormQuestion>of() : questions) {
            Object answer = given.get(question.getId());
            if (!truthy(answer)) {
                answer = Map.of();
            }
            FormAnswer out = new FormAnswer(question.getId(), "", new ArrayList<>());
            String label = question.getLabel();
            if (CHOICE_TYPES.contains(question.getType())) {
                Set<String> picked = new LinkedHashSet<>();
                if (get(answer, "choices") instanceof List<?> choices) {
                    choices.forEach(choice -> picked.add(string(choice)));
                }
                if (picked.stream().anyMatch(choice -> !question.getOptions().contains(choice))) {
                    throw invalid("Choose from the options given for \"" + label + "\"");
                }
                if ("SINGLE_CHOICE".equals(question.getType()) && picked.size() > 1) {
                    throw invalid("Pick one option for \"" + label + "\"");
                }
                if (question.isRequired() && picked.isEmpty()) {
                    throw invalid("Please answer \"" + label + "\"");
                }
                out.setChoices(new ArrayList<>(picked));
                answers.add(out);
                continue;
            }
            String text = trim(textOrEmpty(get(answer, "text")));
            Integer limit = LIMITS.get(question.getType());
            if (text.length() > (limit == null ? 200 : limit)) {
                throw invalid("\"" + label + "\" can be up to " + (limit == null ? "undefined" : limit) + " characters");
            }
            if (question.isRequired() && text.isEmpty()) {
                throw invalid("Please answer \"" + label + "\"");
            }
            if (!text.isEmpty()) {
                switch (question.getType()) {
                    case "LINK" -> {
                        if (!URL_PATTERN.matcher(text).matches()) {
                            throw invalid("\"" + label + "\" needs a full link starting with https://");
                        }
                    }
                    case "NUMBER" -> {
                        if (!NUMBER.matcher(text).matches()) {
                            throw invalid("\"" + label + "\" must be a number");
                        }
                    }
                    case "PHONE" -> {
                        if (!PHONE.matcher(text).matches()) {
                            throw invalid("\"" + label + "\" must be a phone number");
                        }
                    }
                    case "DATE" -> {
                        if (!DATE.matcher(text).matches()) {
                            throw invalid("\"" + label + "\" must be a date");
                        }
                    }
                    default -> {
                        // SHORT and PARAGRAPH take any text.
                    }
                }
            }
            out.setText(text);
            answers.add(out);
        }
        return answers;
    }
}
