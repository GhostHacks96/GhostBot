package me.ghosthacks96.discord.services;

import net.dv8tion.jda.api.JDA;

import net.dv8tion.jda.api.EmbedBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.awt.Color;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GitHubPollingService {
    private final JDA jda;
    private final HttpClient httpClient;
    private final Gson gson;
    private final String githubToken;
    private final String channelId;
    private final Set<String> processedEventIds;
    private final ScheduledExecutorService scheduler;
    private final String repo;

    // Store last check times for each repo
    private final Map<String, OffsetDateTime> lastCheckTimes;

    public GitHubPollingService(JDA jda, String githubToken, String repoID, String channelId) {
        this.jda = jda;
        this.repo = repoID;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new Gson();
        this.githubToken = githubToken;
        this.channelId = channelId;
        this.processedEventIds = new HashSet<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.lastCheckTimes = new HashMap<>();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public String getChannelId() {
        return channelId;
    }

    public void startPolling() {
        int intervalMinutes = 5;

        // Poll for commits/pushes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForNewCommits(repo);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, 0, intervalMinutes, TimeUnit.MINUTES);

        // Poll for comments
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForNewComments(repo);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, 120, intervalMinutes, TimeUnit.SECONDS); // Offset by 30 seconds
    }

    private void checkForNewCommits(String repository) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/commits", repository);
        OffsetDateTime since = lastCheckTimes.get(repository);
        if (since == null) {
            // Default to 1 hour ago if never checked before
            since = OffsetDateTime.now().minusHours(1);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?since=" + since.format(DateTimeFormatter.ISO_INSTANT)))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray commits = gson.fromJson(response.body(), JsonArray.class);

            for (int i = commits.size() - 1; i >= 0; i--) { // Process oldest first
                JsonObject commit = commits.get(i).getAsJsonObject();
                String eventId = repository + "_commit_" + commit.get("sha").getAsString();

                if (!processedEventIds.contains(eventId)) {
                    processedEventIds.add(eventId);
                    sendCommitNotification(repository, commit);
                }
            }

            if (commits.size() > 0) {
                lastCheckTimes.put(repository, OffsetDateTime.now());
            }
        }
    }

    private void checkForNewComments(String repository) throws IOException, InterruptedException {
        // Check issue comments
        checkIssueComments(repository);
        // Check PR comments
        checkPRComments(repository);
    }

    private void checkIssueComments(String repository) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/issues/comments", repository);
        OffsetDateTime since = lastCheckTimes.getOrDefault(repository + "_comments", OffsetDateTime.now().minusHours(1));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?since=" + since.format(DateTimeFormatter.ISO_INSTANT) + "&sort=created&direction=asc"))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray comments = gson.fromJson(response.body(), JsonArray.class);

            for (int i = 0; i < comments.size(); i++) {
                JsonObject comment = comments.get(i).getAsJsonObject();
                String eventId = "issue_comment_" + comment.get("id").getAsString();

                if (!processedEventIds.contains(eventId)) {
                    processedEventIds.add(eventId);
                    sendIssueCommentNotification(repository, comment);
                }
            }

            if (comments.size() > 0) {
                lastCheckTimes.put(repository + "_comments", OffsetDateTime.now());
            }
        }
    }

    private void checkPRComments(String repository) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/pulls/comments", repository);
        OffsetDateTime since = lastCheckTimes.getOrDefault(repository + "_pr_comments", OffsetDateTime.now().minusHours(1));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?since=" + since.format(DateTimeFormatter.ISO_INSTANT) + "&sort=created&direction=asc"))
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonArray comments = gson.fromJson(response.body(), JsonArray.class);

            for (int i = 0; i < comments.size(); i++) {
                JsonObject comment = comments.get(i).getAsJsonObject();
                String eventId = "pr_comment_" + comment.get("id").getAsString();

                if (!processedEventIds.contains(eventId)) {
                    processedEventIds.add(eventId);
                    sendPRCommentNotification(repository, comment);
                }
            }

            if (comments.size() > 0) {
                lastCheckTimes.put(repository + "_pr_comments", OffsetDateTime.now());
            }
        }
    }

    private void sendCommitNotification(String repository, JsonObject commit) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        JsonObject commitData = commit.getAsJsonObject("commit");
        JsonObject author = commitData.getAsJsonObject("author");
        String message = commitData.get("message").getAsString();
        String sha = commit.get("sha").getAsString().substring(0, 7);

        if (message.length() > 100) {
            message = message.substring(0, 97) + "...";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📝 New Commit to " + repository)
                .setColor(Color.GREEN)
                .setTimestamp(OffsetDateTime.parse(author.get("date").getAsString()))
                .addField("Author", author.get("name").getAsString(), true)
                .addField("SHA", sha, true)
                .addField("Message", message, false)
                .setUrl(commit.get("html_url").getAsString());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    private void sendIssueCommentNotification(String repository, JsonObject comment) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        JsonObject user = comment.getAsJsonObject("user");
        String body = comment.get("body").getAsString();
        String issueUrl = comment.get("issue_url").getAsString();
        String issueNumber = issueUrl.substring(issueUrl.lastIndexOf('/') + 1);

        if (body.length() > 200) {
            body = body.substring(0, 197) + "...";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("💬 New Issue Comment in " + repository)
                .setColor(Color.BLUE)
                .setTimestamp(OffsetDateTime.parse(comment.get("created_at").getAsString()))
                .addField("Issue", "#" + issueNumber, true)
                .addField("Author", user.get("login").getAsString(), true)
                .addField("Comment", body, false)
                .setUrl(comment.get("html_url").getAsString());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    private void sendPRCommentNotification(String repository, JsonObject comment) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        JsonObject user = comment.getAsJsonObject("user");
        String body = comment.get("body").getAsString();
        String pullRequestUrl = comment.get("pull_request_url").getAsString();
        String prNumber = pullRequestUrl.substring(pullRequestUrl.lastIndexOf('/') + 1);

        if (body.length() > 200) {
            body = body.substring(0, 197) + "...";
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("💬 New PR Comment in " + repository)
                .setColor(Color.ORANGE)
                .setTimestamp(OffsetDateTime.parse(comment.get("created_at").getAsString()))
                .addField("Pull Request", "#" + prNumber, true)
                .addField("Author", user.get("login").getAsString(), true)
                .addField("Comment", body, false)
                .setUrl(comment.get("html_url").getAsString());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    /**
     * Manually trigger a check for new commits and comments.
     */
    public void manualCheck() {
        try {
            checkForNewCommits(repo);
            checkForNewComments(repo);
        } catch (Exception e) {
            System.err.println("Manual check failed for repo: " + repo + ", error: " + e.getMessage());
        }
    }


    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        System.out.println("GitHubPollingService shutdown complete.");
    }
}