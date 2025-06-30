package me.ghosthacks96.discord.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ghosthacks96.discord.commands.GitHubTrackCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GitHubPackagePollingService {

    private final JDA jda;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final String githubToken; // Optional: for higher rate limits

    // Store the latest release info to compare against
    private final ConcurrentMap<String, String> lastPackageVersions = new ConcurrentHashMap<>();

    public GitHubPackagePollingService(JDA jda, String githubToken) {
        this.jda = jda;
        this.githubToken = githubToken;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.scheduler = Executors.newScheduledThreadPool(2);

        // Start polling every 10 minutes
        startPolling();
    }

    private void startPolling() {
        scheduler.scheduleAtFixedRate(this::checkAllPackages, 1, 10, TimeUnit.MINUTES);
        System.out.println("GitHub Package Polling Service started - checking every 10 minutes");
    }

    private void checkAllPackages() {
        try {
            for (GitHubTrackCommand.TrackedPackage pkg : GitHubTrackCommand.trackedPackages.values()) {
                checkPackageForUpdates(pkg);

                // Add delay between requests to respect rate limits
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            System.err.println("Error during package polling: " + e.getMessage());
        }
    }

    public void checkPackageForUpdates(GitHubTrackCommand.TrackedPackage trackedPackage) {
        try {
            String packageName = trackedPackage.name;

            // First, try to find the GitHub repository for this package
            String repoPath = findRepositoryForPackage(packageName);
            if (repoPath == null) {
                System.out.println("Could not find GitHub repository for package: " + packageName);
                return;
            }

            // Get the latest release from the repository
            JsonObject latestRelease = getLatestRelease(repoPath);
            if (latestRelease == null) {
                System.out.println("No releases found for repository: " + repoPath);
                return;
            }

            String latestVersion = latestRelease.get("tag_name").getAsString();
            String lastKnownVersion = lastPackageVersions.get(packageName.toLowerCase());

            // Check if this is a new version
            if (lastKnownVersion == null || !latestVersion.equals(lastKnownVersion)) {
                lastPackageVersions.put(packageName.toLowerCase(), latestVersion);

                // Only send notification if we had a previous version (not first run)
                if (lastKnownVersion != null) {
                    sendPackageUpdateNotification(trackedPackage, latestRelease, repoPath);
                } else {
                    System.out.println("Initial version stored for " + packageName + ": " + latestVersion);
                }
            }

        } catch (Exception e) {
            System.err.println("Error checking package " + trackedPackage.name + ": " + e.getMessage());
        }
    }

    private String findRepositoryForPackage(String packageName) {
        try {
            // Search GitHub for repositories with the package name
            String searchUrl = "https://api.github.com/search/repositories?q=" + packageName + "+in:name&sort=stars&order=desc";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GhostBot-PackageTracker");

            if (githubToken != null && !githubToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + githubToken);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject searchResult = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray items = searchResult.getAsJsonArray("items");

                if (items.size() > 0) {
                    // Return the most starred repository that matches
                    JsonObject topRepo = items.get(0).getAsJsonObject();
                    return topRepo.get("full_name").getAsString();
                }
            }

        } catch (Exception e) {
            System.err.println("Error searching for repository: " + e.getMessage());
        }

        return null;
    }

    private JsonObject getLatestRelease(String repoPath) {
        try {
            String apiUrl = "https://api.github.com/repos/" + repoPath + "/releases/latest";

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GhostBot-PackageTracker");

            if (githubToken != null && !githubToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + githubToken);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } else if (response.statusCode() == 404) {
                System.out.println("No releases found for repository: " + repoPath);
            } else {
                System.err.println("GitHub API error for " + repoPath + ": " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("Error fetching latest release: " + e.getMessage());
        }

        return null;
    }

    private void sendPackageUpdateNotification(GitHubTrackCommand.TrackedPackage trackedPackage,
                                               JsonObject release, String repoPath) {
        try {
            TextChannel channel = jda.getTextChannelById(trackedPackage.channelId);
            if (channel == null) {
                System.err.println("Channel not found for package notification: " + trackedPackage.channelId);
                return;
            }

            // Parse release information
            String version = release.get("tag_name").getAsString();
            String releaseName = release.has("name") && !release.get("name").isJsonNull()
                    ? release.get("name").getAsString() : version;
            String releaseUrl = release.get("html_url").getAsString();
            String publishedAt = release.get("published_at").getAsString();
            String body = release.has("body") && !release.get("body").isJsonNull()
                    ? release.get("body").getAsString() : "No release notes provided.";

            // Truncate body if too long
            if (body.length() > 1000) {
                body = body.substring(0, 997) + "...";
            }

            // Parse published date
            OffsetDateTime publishedDate = OffsetDateTime.parse(publishedAt);
            String formattedDate = publishedDate.format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm z"));

            // Get author information
            String authorName = "Unknown";
            String authorUrl = "";
            String authorAvatar = "";

            if (release.has("author") && !release.get("author").isJsonNull()) {
                JsonObject author = release.getAsJsonObject("author");
                authorName = author.get("login").getAsString();
                authorUrl = author.get("html_url").getAsString();
                authorAvatar = author.get("avatar_url").getAsString();
            }

            // Build the embed
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("📦 " + trackedPackage.name + " - New Release!", releaseUrl)
                    .setDescription("**" + releaseName + "**\n\n" + body)
                    .setColor(Color.GREEN)
                    .addField("🏷️ Version", version, true)
                    .addField("📅 Published", formattedDate, true)
                    .addField("👤 Author", "[" + authorName + "](" + authorUrl + ")", true)
                    .addField("🔗 Repository", "[" + repoPath + "](https://github.com/" + repoPath + ")", true)
                    .setFooter("GitHub Package Tracker", "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png")
                    .setTimestamp(publishedDate);

            if (!authorAvatar.isEmpty()) {
                embed.setThumbnail(authorAvatar);
            }

            // Add download count if available
            if (release.has("assets")) {
                JsonArray assets = release.getAsJsonArray("assets");
                int totalDownloads = 0;
                for (JsonElement assetElement : assets) {
                    JsonObject asset = assetElement.getAsJsonObject();
                    totalDownloads += asset.get("download_count").getAsInt();
                }
                if (totalDownloads > 0) {
                    embed.addField("📥 Downloads", String.format("%,d", totalDownloads), true);
                }
            }

            // Send the notification
            channel.sendMessageEmbeds(embed.build()).queue(
                    success -> System.out.println("Sent package update notification for " + trackedPackage.name + " v" + version),
                    error -> System.err.println("Failed to send package notification: " + error.getMessage())
            );

        } catch (Exception e) {
            System.err.println("Error sending package update notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void manualCheckPackage(String packageName) {
        GitHubTrackCommand.TrackedPackage trackedPackage = GitHubTrackCommand.trackedPackages.get(packageName.toLowerCase());
        if (trackedPackage != null) {
            scheduler.execute(() -> checkPackageForUpdates(trackedPackage));
        } else {
            System.err.println("Package not found for manual check: " + packageName);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        System.out.println("GitHub Package Polling Service shut down");
    }
}