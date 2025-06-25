package me.ghosthacks96.discord.events;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AntiSpamListener extends ListenerAdapter {

    // Configuration
    private static final int MAX_MESSAGES = 5; // Max messages in time window
    private static final int TIME_WINDOW_SECONDS = 10; // Time window in seconds
    private static final int MUTE_DURATION_MINUTES = 5; // Mute duration
    private static final int SIMILAR_MESSAGE_THRESHOLD = 3; // Similar messages threshold

    // Storage for user message tracking
    private final Map<String, List<MessageData>> userMessages = new ConcurrentHashMap<>();
    private final Map<String, Instant> mutedUsers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // Clean up old data every 30 seconds
    public AntiSpamListener() {
        scheduler.scheduleAtFixedRate(this::cleanupOldData, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Check if channel should be considered "private" and ignored by anti-spam
     * You can customize this logic based on your server setup
     */
    private boolean isPrivateChannel(TextChannel channel) {
        String channelName = channel.getName().toLowerCase();

        // Check channel name for private indicators
        if (channelName.contains("private") ||
                channelName.contains("staff") ||
                channelName.contains("mod") ||
                channelName.contains("admin") ||
                channelName.contains("vip")) {
            return true;
        }

        // Check category name for private indicators
        if (channel.getParentCategory() != null) {
            String categoryName = channel.getParentCategory().getName().toLowerCase();
            if (categoryName.contains("private") ||
                    categoryName.contains("staff") ||
                    categoryName.contains("mod") ||
                    categoryName.contains("admin") ||
                    categoryName.contains("vip")) {
                return true;
            }
        }

        // Check if channel has restricted permissions (only certain roles can see it)
        // This checks if @everyone can view the channel
        return !channel.getGuild().getPublicRole()
                .getPermissions(channel)
                .contains(Permission.VIEW_CHANNEL);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore bots and DMs
        if (event.getAuthor().isBot() || !event.isFromGuild()) {
            return;
        }

        // Ignore private channels (channels with "private" in name or category)
        if (isPrivateChannel(event.getChannel().asTextChannel())) {
            return;
        }

        // Ignore command messages (messages starting with command prefixes)
        String content = event.getMessage().getContentRaw();
        if (content.startsWith("!") || content.startsWith("/") || content.startsWith("?")) {
            return;
        }

        Member member = event.getMember();
        if (member == null) return;

        // Don't check admins/moderators
        if (member.hasPermission(Permission.ADMINISTRATOR) ||
                member.hasPermission(Permission.NICKNAME_CHANGE)) {
            return;
        }

        String userId = event.getAuthor().getId();
        String guildId = event.getGuild().getId();
        String userKey = guildId + ":" + userId;

        // Check if user is currently muted
        if (mutedUsers.containsKey(userKey)) {
            // Delete message from muted user
            event.getMessage().delete().queue();
            return;
        }

        // Add message to tracking
        MessageData msgData = new MessageData(
                event.getMessage().getContentRaw(),
                Instant.now(),
                event.getChannel().getId()
        );

        userMessages.computeIfAbsent(userKey, k -> new ArrayList<>()).add(msgData);

        // Check for spam
        if (isSpamming(userKey)) {
            muteUser(event, member);
        }
    }

    private boolean isSpamming(String userKey) {
        List<MessageData> messages = userMessages.get(userKey);
        if (messages == null || messages.size() < MAX_MESSAGES) {
            return false;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofSeconds(TIME_WINDOW_SECONDS));

        // Count recent messages
        long recentMessages = messages.stream()
                .filter(msg -> msg.timestamp.isAfter(windowStart))
                .count();

        // Check message frequency spam
        if (recentMessages >= MAX_MESSAGES) {
            return true;
        }

        // Check for similar message spam
        List<String> recentContent = messages.stream()
                .filter(msg -> msg.timestamp.isAfter(windowStart))
                .map(msg -> msg.content.toLowerCase().trim())
                .filter(content -> !content.isEmpty())
                .toList();

        if (recentContent.size() >= SIMILAR_MESSAGE_THRESHOLD) {
            // Check if messages are too similar
            String firstMsg = recentContent.get(0);
            long similarCount = recentContent.stream()
                    .filter(content -> calculateSimilarity(firstMsg, content) > 0.8)
                    .count();

            return similarCount >= SIMILAR_MESSAGE_THRESHOLD;
        }

        return false;
    }

    private void muteUser(MessageReceivedEvent event, Member member) {
        Guild guild = event.getGuild();
        String userId = member.getId();
        String guildId = guild.getId();
        String userKey = guildId + ":" + userId;

        // Find or create muted role
        Role mutedRole = findOrCreateMutedRole(guild);
        if (mutedRole == null) {
            return;
        }

        // Add muted role
        guild.addRoleToMember(member, mutedRole).queue(
                success -> {
                    // Mark user as muted
                    mutedUsers.put(userKey, Instant.now().plus(Duration.ofMinutes(MUTE_DURATION_MINUTES)));

                    // Delete recent spam messages
                    deleteRecentMessages(event, userKey);

                    // Send notification
                    event.getChannel().sendMessage(
                            "🔇 " + member.getAsMention() + " has been muted for " +
                                    MUTE_DURATION_MINUTES + " minutes due to spamming."
                    ).queue();

                    // Schedule unmute
                    scheduler.schedule(() -> unmuteUser(guild, member, mutedRole),
                            MUTE_DURATION_MINUTES, TimeUnit.MINUTES);
                },
                error -> {
                    System.err.println("Failed to mute user: " + error.getMessage());
                }
        );
    }

    private Role findOrCreateMutedRole(Guild guild) {
        // Try to find existing muted role
        List<Role> mutedRoles = guild.getRolesByName("Muted", true);
        if (!mutedRoles.isEmpty()) {
            return mutedRoles.get(0);
        }

        // Create muted role if it doesn't exist
        try {
            Role mutedRole = guild.createRole()
                    .setName("Muted")
                    .setColor(0x818386)
                    .setPermissions(Permission.MESSAGE_HISTORY)
                    .complete();

            // Set permissions for all text channels
            guild.getTextChannels().forEach(channel -> {
                channel.getManager().putPermissionOverride(mutedRole, null,
                                EnumSet.of(Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION))
                        .queue();
            });

            return mutedRole;
        } catch (Exception e) {
            System.err.println("Failed to create muted role: " + e.getMessage());
            return null;
        }
    }

    private void deleteRecentMessages(MessageReceivedEvent event, String userKey) {
        List<MessageData> messages = userMessages.get(userKey);
        if (messages == null) return;

        Instant cutoff = Instant.now().minus(Duration.ofSeconds(TIME_WINDOW_SECONDS));

        // Delete the current message
        event.getMessage().delete().queue();

        // Note: Bulk deletion of old messages requires message history retrieval
        // This is a simplified version - you might want to implement message ID tracking
        // for more sophisticated message deletion
    }

    private void unmuteUser(Guild guild, Member member, Role mutedRole) {
        String userKey = guild.getId() + ":" + member.getId();

        guild.removeRoleFromMember(member, mutedRole).queue(
                success -> {
                    mutedUsers.remove(userKey);

                    // Try to send DM to user
                    member.getUser().openPrivateChannel().queue(channel -> {
                        channel.sendMessage("You have been unmuted in " + guild.getName() +
                                ". Please follow the server rules to avoid future mutes.").queue();
                    });
                },
                error -> {
                    System.err.println("Failed to unmute user: " + error.getMessage());
                    mutedUsers.remove(userKey); // Remove from tracking anyway
                }
        );
    }

    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;

        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;

        return (maxLen - levenshteinDistance(s1, s2)) / (double) maxLen;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(Math.min(
                                    dp[i - 1][j] + 1,
                                    dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                    );
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }

    private void cleanupOldData() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));

        // Clean up old message data
        userMessages.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(msg -> msg.timestamp.isBefore(cutoff));
            return entry.getValue().isEmpty();
        });

        // Clean up expired mutes
        mutedUsers.entrySet().removeIf(entry -> entry.getValue().isBefore(Instant.now()));
    }

    // Helper class to store message data
    private static class MessageData {
        final String content;
        final Instant timestamp;
        final String channelId;

        MessageData(String content, Instant timestamp, String channelId) {
            this.content = content;
            this.timestamp = timestamp;
            this.channelId = channelId;
        }
    }

    // Method to shutdown the scheduler when bot shuts down
    public void shutdown() {
        scheduler.shutdown();
    }
}