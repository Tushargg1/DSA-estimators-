package com.dsatracker.github;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GitHubMarkdown {
    private static final Pattern BACKTICKS = Pattern.compile("`+");

    public String render(GitHubSolutionCapture capture) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(metadata(capture.getProblemName())).append("\n\n")
                .append("- **Platform:** ").append(capture.getPlatform()).append('\n')
                .append("- **Difficulty:** ").append(metadata(
                        capture.getDifficulty() == null ? "Unspecified" : capture.getDifficulty()))
                .append('\n')
                .append("- **Patterns:** ").append(metadata(capture.getPatternSlug())).append('\n')
                .append("- **Solved at:** ").append(capture.getSolvedAtUtc()).append('\n')
                .append("- **Canonical problem:** [")
                .append(metadata(capture.getProblemName())).append("](<")
                .append(capture.getProblemUrl()).append(">)\n")
                .append("- **Language:** ").append(metadata(capture.getLanguage())).append('\n');
        List<String> tags = capture.getTags();
        if (tags != null && !tags.isEmpty()) {
            out.append("- **Tags:** ").append(tags.stream().map(GitHubMarkdown::metadata)
                    .reduce((left, right) -> left + ", " + right).orElse("")).append('\n');
        }
        out.append('\n');
        String source = capture.getSourceCode();
        if (source == null) return out.append("_Source code was not provided._\n").toString();
        String fence = fence(source);
        return out.append(fence).append(languageHint(capture.getLanguage())).append('\n')
                .append(source).append(source.endsWith("\n") ? "" : "\n")
                .append(fence).append('\n').toString();
    }

    private static String fence(String source) {
        int longest = 0;
        Matcher matcher = BACKTICKS.matcher(source);
        while (matcher.find()) longest = Math.max(longest, matcher.end() - matcher.start());
        return "`".repeat(Math.max(3, longest + 1));
    }

    private static String languageHint(String language) {
        return language == null ? "" : language.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_+.#-]", "");
    }

    private static String metadata(String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)) safe.append(' ');
            else if ("\\`*_{}[]<>#|".indexOf(current) >= 0) safe.append('\\').append(current);
            else safe.append(current);
        }
        return safe.toString().replaceAll("\\s+", " ").trim();
    }
}
