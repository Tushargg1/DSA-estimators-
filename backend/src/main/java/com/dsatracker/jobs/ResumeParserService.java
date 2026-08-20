package com.dsatracker.jobs;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses PDF resumes to extract text and automatically classify a candidate's
 * profile into categories (Java Developer, Python Developer, Data Science, etc.)
 * based on detected skills and keywords.
 */
@Service
public class ResumeParserService {
    private static final Logger log = LoggerFactory.getLogger(ResumeParserService.class);
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    // Profile categories with associated keywords (ordered by priority)
    private static final Map<String, List<String>> PROFILE_CATEGORIES = new LinkedHashMap<>();

    static {
        PROFILE_CATEGORIES.put("Java Developer", List.of(
                "java", "spring", "spring boot", "hibernate", "jpa", "maven", "gradle",
                "microservices", "junit", "tomcat", "servlet", "jdbc", "jms", "kafka",
                "spring cloud", "spring security", "j2ee", "javafx", "multithreading"
        ));
        PROFILE_CATEGORIES.put("Python Developer", List.of(
                "python", "django", "flask", "fastapi", "celery", "pytest", "pip",
                "virtualenv", "asyncio", "sqlalchemy", "pydantic", "uvicorn", "gunicorn"
        ));
        PROFILE_CATEGORIES.put("Data Science", List.of(
                "data science", "machine learning", "deep learning", "pandas", "numpy",
                "scikit-learn", "tensorflow", "pytorch", "keras", "nlp", "computer vision",
                "statistics", "r programming", "jupyter", "matplotlib", "seaborn",
                "feature engineering", "model training", "neural network", "regression",
                "classification", "clustering", "data analysis", "power bi", "tableau"
        ));
        PROFILE_CATEGORIES.put("Frontend Developer", List.of(
                "react", "angular", "vue", "svelte", "nextjs", "next.js", "nuxt",
                "javascript", "typescript", "html", "css", "tailwind", "sass", "webpack",
                "vite", "redux", "zustand", "graphql", "responsive design", "figma"
        ));
        PROFILE_CATEGORIES.put("Backend Developer", List.of(
                "node", "express", "nestjs", "golang", "go", "rust", "c#", ".net",
                "asp.net", "ruby", "rails", "php", "laravel", "api", "rest", "grpc",
                "websocket", "authentication", "authorization"
        ));
        PROFILE_CATEGORIES.put("DevOps Engineer", List.of(
                "docker", "kubernetes", "k8s", "terraform", "ansible", "jenkins",
                "ci/cd", "github actions", "gitlab ci", "aws", "azure", "gcp",
                "linux", "nginx", "monitoring", "prometheus", "grafana", "helm",
                "infrastructure as code", "cloudformation", "ecs", "eks"
        ));
        PROFILE_CATEGORIES.put("Full Stack Developer", List.of(
                "full stack", "fullstack", "full-stack", "mern", "mean", "lamp"
        ));
        PROFILE_CATEGORIES.put("Mobile Developer", List.of(
                "android", "ios", "swift", "kotlin", "flutter", "dart", "react native",
                "mobile", "xcode", "gradle", "cocoapods", "firebase"
        ));
        PROFILE_CATEGORIES.put("Cloud Engineer", List.of(
                "aws", "azure", "gcp", "cloud", "s3", "ec2", "lambda", "cloudfront",
                "dynamo", "rds", "sqs", "sns", "iam", "vpc", "route 53"
        ));
    }

    /**
     * Result of parsing a resume: extracted text, detected role, and keywords.
     */
    public record ParseResult(
            String extractedText,
            String suggestedRole,
            String detectedKeywords,
            Map<String, Integer> categoryScores
    ) { }

    /**
     * Parse a PDF resume file and return extracted information.
     */
    public ParseResult parseResume(MultipartFile file) throws IOException {
        validateFile(file);

        String text = extractTextFromPdf(file);
        if (text.isBlank()) {
            throw new IOException("Could not extract any text from the PDF. The file may be image-based or corrupted.");
        }

        Map<String, Integer> scores = scoreCategoriesFromText(text);
        String suggestedRole = detectBestRole(scores);
        Set<String> keywords = extractAllKeywords(text);

        return new ParseResult(
                truncate(text, 50000),
                suggestedRole,
                String.join(", ", keywords),
                scores
        );
    }

    /**
     * Extract plain text content from a PDF file.
     */
    private String extractTextFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            // Normalize whitespace
            return text.replaceAll("[ \\t]+", " ")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
        }
    }

    /**
     * Score each profile category based on keyword frequency in the text.
     */
    private Map<String, Integer> scoreCategoriesFromText(String text) {
        String lowerText = text.toLowerCase();
        Map<String, Integer> scores = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : PROFILE_CATEGORIES.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                // Count occurrences of the keyword (word boundary aware for short keywords)
                Pattern pattern = keyword.length() <= 3
                        ? Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
                long count = pattern.matcher(lowerText).results().count();
                score += (int) count;
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }
        return scores;
    }

    /**
     * Determine the best role based on category scores.
     * Uses a weighted approach: highest score wins, but requires minimum threshold.
     */
    private String detectBestRole(Map<String, Integer> scores) {
        if (scores.isEmpty()) {
            return "Software Developer";
        }

        // Find the top category
        Map.Entry<String, Integer> best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        if (best == null || best.getValue() < 2) {
            return "Software Developer";
        }

        // Check if "Full Stack" should override when both frontend and backend score well
        Integer frontendScore = scores.getOrDefault("Frontend Developer", 0);
        Integer backendScore = scores.getOrDefault("Backend Developer", 0);
        Integer javaScore = scores.getOrDefault("Java Developer", 0);
        Integer pythonScore = scores.getOrDefault("Python Developer", 0);

        if (frontendScore >= 3 && (backendScore >= 3 || javaScore >= 3 || pythonScore >= 3)) {
            return "Full Stack Developer";
        }

        return best.getKey();
    }

    /**
     * Extract all tech keywords found in the text across all categories.
     */
    private Set<String> extractAllKeywords(String text) {
        String lowerText = text.toLowerCase();
        Set<String> found = new LinkedHashSet<>();

        for (List<String> categoryKeywords : PROFILE_CATEGORIES.values()) {
            for (String keyword : categoryKeywords) {
                Pattern pattern = keyword.length() <= 3
                        ? Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(lowerText).find()) {
                    found.add(keyword);
                }
            }
        }

        // Also extract common job-title tokens from the first 2000 chars (usually the header)
        String header = lowerText.substring(0, Math.min(2000, lowerText.length()));
        Set<String> rolePhrases = Set.of(
                "software engineer", "senior engineer", "staff engineer", "tech lead",
                "engineering manager", "data engineer", "ml engineer", "sre",
                "platform engineer", "solutions architect", "principal engineer"
        );
        for (String phrase : rolePhrases) {
            if (header.contains(phrase)) {
                found.add(phrase);
            }
        }

        return found;
    }

    private void validateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("No file uploaded.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IOException("File too large. Maximum size is 5 MB.");
        }
        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename();
        boolean isPdf = "application/pdf".equals(contentType)
                || (originalName != null && originalName.toLowerCase().endsWith(".pdf"));
        if (!isPdf) {
            throw new IOException("Only PDF files are supported. Please upload a .pdf file.");
        }
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
