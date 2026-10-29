package com.lifeorganizer.ai.agent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the model's final text against what actually happened in the turn. The prompt already forbids these
 * replies, but the model does not always follow it: it has written a proposal as JSON text without calling the tool
 * (so the user got no Apply button) and has claimed a change was applied when nothing was. A problem is sent back to
 * the model once as a correction; the patterns are English only and deliberately narrow, so ordinary answers pass.
 */
final class ReplyGuard {

    /** A JSON object that carries a tool's argument names. */
    private static final Pattern TOOL_JSON =
        Pattern.compile("\\{[\\s\\S]*\"(goalId|blockId|rationale|events|changes)\"\\s*:");

    private static final String DONE_VERBS = "added|cancell?ed|moved|changed|applied|postponed|updated|raised|lowered"
        + "|removed|rescheduled|saved";

    /**
     * A weekly summary reports what already happened to the plan ("Gym has been postponed twice", "two sessions have
     * been moved this week"). A passive claim followed in the same sentence by a count or a past period is such a
     * report, not a claim that the assistant just changed something.
     */
    private static final String REPORTED_HISTORY = "(?![^.!?]*\\b(?:once|twice|times|this week|last week|so far"
        + "|yesterday|on (?:mon|tues|wednes|thurs|fri|satur|sun)day)\\b)";

    /** "I've added ...", "... have been applied", "it's done". */
    private static final Pattern DONE_CLAIM = Pattern.compile(
        "\\bI(['\u2019]ve| have)\\s+(just\\s+|already\\s+|now\\s+)?(" + DONE_VERBS + ")\\b"
            + "|\\b(has|have) been (" + DONE_VERBS + ")\\b" + REPORTED_HISTORY
            + "|\\bit(['\u2019]s| is) done\\b",
        Pattern.CASE_INSENSITIVE);

    /** "press Apply", "Apply or Reject" - only true when this turn recorded a proposal. */
    private static final Pattern APPLY_MENTION = Pattern.compile(
        "\\b(press|click|hit|tap)\\s+\\W{0,2}apply\\b|\\bapply\\W{0,2}\\s+or\\s+\\W{0,2}reject\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");

    private ReplyGuard() {
    }

    /** The correction to send back to the model, or empty when the reply matches what happened in the turn. */
    static Optional<String> problem(String reply, boolean proposalRecorded) {
        boolean json = TOOL_JSON.matcher(reply).find();
        boolean doneClaim = DONE_CLAIM.matcher(reply).find();
        if (proposalRecorded) {
            if (doneClaim) {
                return Optional.of("You wrote that the change is already done. It is only proposed. Rewrite your reply: "
                    + "say what you propose and that the user can press Apply. Plain sentences, no JSON.");
            }
            if (json) {
                return Optional.of("Rewrite your reply in plain sentences without JSON: say what you propose and that "
                    + "the user can press Apply.");
            }
            return Optional.empty();
        }
        if (json) {
            return Optional.of("You wrote tool arguments as text. The user cannot use that and has no Apply button. "
                + "Call propose_changes as a tool now with those arguments, then reply in plain "
                + "sentences without JSON.");
        }
        if (doneClaim || APPLY_MENTION.matcher(reply).find()) {
            return Optional.of("No proposal was recorded in this turn, so nothing changed and the user has no Apply "
                + "button. If a change is wanted, call propose_changes now. Otherwise reply "
                + "without claiming a change and without mentioning Apply.");
        }
        return Optional.empty();
    }

    /** The chat shows text as written, so Markdown emphasis markers would appear literally. */
    static String clean(String reply) {
        return BOLD.matcher(reply)
            .replaceAll(match -> Matcher.quoteReplacement(match.group(1) != null ? match.group(1) : match.group(2)))
            .strip();
    }
}
