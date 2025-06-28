package me.ghosthacks96.discord.commands;

import me.ghosthacks96.discord.GhostBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class GitHubTrackCommand extends ListenerAdapter {

    // In-memory storage for tracked items (consider using a database for persistence)
    private static final ConcurrentMap<String, TrackedRepository> trackedRepos = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, TrackedPackage> trackedPackages = new ConcurrentHashMap<>();

    public static CommandData getCommandData() {
        OptionData typeOption = new OptionData(OptionType.STRING, "type", "Type of item to track", true)
                .addChoice("Repository", "repo")
                .addChoice("Package", "package");


        return Commands.slash("github", "GitHub repository and package tracking commands")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                .addSubcommands(
                        new SubcommandData("track", "Add a repository or package to tracking list")
                                .addOptions(typeOption)
                                .addOption(OptionType.STRING, "name", "Repository (owner/repo) or package name", true),

                        new SubcommandData("check", "Manually check for updates")
                                .addOption(OptionType.STRING, "type", "Type to check", true)
                                .addOption(OptionType.STRING, "name", "Name to check", true),

                        new SubcommandData("list", "List all tracked items"),

                        new SubcommandData("untrack", "Remove item from tracking")
                                .addOptions(typeOption)
                                .addOption(OptionType.STRING, "name", "Name to untrack", true),

                        new SubcommandData("help", "Show help information")
                );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("github") || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            return;
        }

        // Defer reply to prevent timeout
        event.deferReply().queue();

        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) {
            event.getHook().editOriginal("❌ This command can only be used in servers.").queue();
            return;
        }

        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.getHook().editOriginal("❌ No subcommand specified.").queue();
            return;
        }

        try {
            switch (subcommand) {
                case "track" -> handleTrackCommand(event);
                case "check" -> handleCheckCommand(event);
                case "list" -> handleListCommand(event);
                case "untrack" -> handleUntrackCommand(event);
                case "help" -> handleHelpCommand(event);
                default -> event.getHook().editOriginal("❌ Unknown subcommand.").queue();
            }
        } catch (Exception e) {
            System.err.println("Error handling GitHub command: " + e.getMessage());
            e.printStackTrace();
            event.getHook().editOriginal("❌ An error occurred while processing your request. Please try again.").queue();
        }
    }

    private void handleTrackCommand(SlashCommandInteractionEvent event) {
        String type = event.getOption("type").getAsString();
        String name = event.getOption("name").getAsString();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🔍 GitHub Tracker")
                .setColor(Color.GREEN)
                .setFooter("Requested by " + event.getUser().getEffectiveName(),
                        event.getUser().getEffectiveAvatarUrl())
                .setTimestamp(java.time.OffsetDateTime.now());

        switch (type) {
            case "repo" -> {
                if (!isValidRepoFormat(name)) {
                    embed.setColor(Color.RED)
                            .setDescription("❌ Invalid repository format. Use `owner/repository` format.\n\n" +
                                    "**Example:** `microsoft/vscode`");
                } else {
                    TrackedRepository repo = new TrackedRepository(name, event.getGuild().getId(),
                            event.getChannel().getId());
                    trackedRepos.put(name.toLowerCase(), repo);
                    // Notify GhostBot to start polling for this repo
                    GhostBot.getInstance().addRepositoryPolling(event.getChannel().getId(), name.toLowerCase());
                    embed.setDescription("✅ Successfully added repository `" + name + "` to tracking list!")
                            .addField("📦 Repository", name, true)
                            .addField("📅 Added", java.time.OffsetDateTime.now()
                                    .format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")), true)
                            .addField("🔔 Notifications", "This channel will receive updates", false)
                            .addField("📊 Tracking",
                                    "• New releases\n• Push events\n• Issue comments\n• Pull request updates", false);
                }
            }
            case "package" -> {
                TrackedPackage pkg = new TrackedPackage(name, event.getGuild().getId(),
                        event.getChannel().getId());
                trackedPackages.put(name.toLowerCase(), pkg);

                embed.setDescription("✅ Successfully added package `" + name + "` to tracking list!")
                        .addField("📦 Package", name, true)
                        .addField("📅 Added", java.time.OffsetDateTime.now()
                                .format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")), true)
                        .addField("🔔 Notifications", "This channel will receive updates", false)
                        .addField("📊 Tracking", "• New package releases\n• Version updates", false);
            }
            default -> {
                embed.setColor(Color.RED)
                        .setDescription("❌ Invalid type. Use `repo` or `package`.");
            }
        }

        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private void handleCheckCommand(SlashCommandInteractionEvent event) {
        String type = event.getOption("type").getAsString();
        String name = event.getOption("name").getAsString();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🔄 Manual Check")
                .setColor(Color.BLUE)
                .setFooter("Requested by " + event.getUser().getEffectiveName(),
                        event.getUser().getEffectiveAvatarUrl())
                .setTimestamp(java.time.OffsetDateTime.now());

        switch (type.toLowerCase()) {
            case "repo" -> {
                if (trackedRepos.containsKey(name.toLowerCase())) {
                    embed.setDescription("🔄 Checking repository `" + name + "` for updates...")
                            .addField("📦 Repository", name, true)
                            .addField("⏰ Status", "Checking for new releases, commits, and issues...", false);

                    // TODO: Implement actual GitHub API checking logic here
                } else {
                    embed.setColor(Color.RED)
                            .setDescription("❌ Repository `" + name + "` is not being tracked.")
                            .addField("💡 Tip", "Use `/github track repo " + name + "` to start tracking it.", false);
                }
            }
            case "package" -> {
                if (trackedPackages.containsKey(name.toLowerCase())) {
                    embed.setDescription("🔄 Checking package `" + name + "` for new releases...")
                            .addField("📦 Package", name, true)
                            .addField("⏰ Status", "Checking for version updates...", false);

                    // TODO: Implement actual package checking logic here
                } else {
                    embed.setColor(Color.RED)
                            .setDescription("❌ Package `" + name + "` is not being tracked.")
                            .addField("💡 Tip", "Use `/github track package " + name + "` to start tracking it.", false);
                }
            }
            default -> {
                embed.setColor(Color.RED)
                        .setDescription("❌ Invalid type. Use `repo` or `package`.");
            }
        }

        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private void handleListCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📋 Tracked Items")
                .setColor(Color.CYAN)
                .setFooter("Requested by " + event.getUser().getEffectiveName(),
                        event.getUser().getEffectiveAvatarUrl())
                .setTimestamp(java.time.OffsetDateTime.now());

        StringBuilder repoList = new StringBuilder();
        StringBuilder packageList = new StringBuilder();

        String guildId = event.getGuild().getId();

        // Filter by current guild
        trackedRepos.values().stream()
                .filter(repo -> repo.guildId.equals(guildId))
                .forEach(repo -> repoList.append("• ").append(repo.name).append("\n"));

        trackedPackages.values().stream()
                .filter(pkg -> pkg.guildId.equals(guildId))
                .forEach(pkg -> packageList.append("• ").append(pkg.name).append("\n"));

        if (repoList.length() == 0 && packageList.length() == 0) {
            embed.setDescription("📭 No items are currently being tracked in this server.")
                    .addField("🚀 Get Started", "Use `/github track` to start tracking repositories or packages!", false);
        } else {
            if (repoList.length() > 0) {
                embed.addField("📦 Repositories (" +
                                trackedRepos.values().stream()
                                        .mapToInt(repo -> repo.guildId.equals(guildId) ? 1 : 0)
                                        .sum() + ")",
                        repoList.toString(), false);
            }
            if (packageList.length() > 0) {
                embed.addField("📋 Packages (" +
                                trackedPackages.values().stream()
                                        .mapToInt(pkg -> pkg.guildId.equals(guildId) ? 1 : 0)
                                        .sum() + ")",
                        packageList.toString(), false);
            }
        }

        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private void handleUntrackCommand(SlashCommandInteractionEvent event) {
        String type = event.getOption("type").getAsString();
        String name = event.getOption("name").getAsString();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🗑️ Untrack Item")
                .setFooter("Requested by " + event.getUser().getEffectiveName(),
                        event.getUser().getEffectiveAvatarUrl())
                .setTimestamp(java.time.OffsetDateTime.now());

        boolean removed = false;

        switch (type) {
            case "repo" -> {
                removed = trackedRepos.remove(name.toLowerCase()) != null;
                if (removed) {
                    GhostBot.getInstance().removeRepositoryPolling(name);
                    embed.setColor(Color.GREEN)
                            .setDescription("✅ Successfully removed repository `" + name + "` from tracking list.");
                } else {
                    embed.setColor(Color.RED)
                            .setDescription("❌ Repository `" + name + "` was not found in tracking list.");
                }
            }
            case "package" -> {
                removed = trackedPackages.remove(name.toLowerCase()) != null;
                if (removed) {
                    embed.setColor(Color.GREEN)
                            .setDescription("✅ Successfully removed package `" + name + "` from tracking list.");
                } else {
                    embed.setColor(Color.RED)
                            .setDescription("❌ Package `" + name + "` was not found in tracking list.");
                }
            }
            default -> {
                embed.setColor(Color.RED)
                        .setDescription("❌ Invalid type. Use `repo` or `package`.");
            }
        }

        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private void handleHelpCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📚 GitHub Tracker Bot - Help")
                .setDescription("Track GitHub repositories and packages for updates and notifications.")
                .setColor(Color.BLUE)
                .setFooter("Requested by " + event.getUser().getEffectiveName(),
                        event.getUser().getEffectiveAvatarUrl())
                .setTimestamp(java.time.OffsetDateTime.now())
                .addField("📦 Tracking Commands",
                        "`/github track repo <owner/repo>` - Track a GitHub repository\n" +
                                "`/github track package <name>` - Track a package for releases\n" +
                                "`/github untrack repo <owner/repo>` - Stop tracking a repository\n" +
                                "`/github untrack package <name>` - Stop tracking a package", false)
                .addField("🔍 Management Commands",
                        "`/github check repo <owner/repo>` - Manually check repository for updates\n" +
                                "`/github check package <name>` - Manually check package for releases\n" +
                                "`/github list` - Show all tracked items in this server", false)
                .addField("🔔 Automatic Tracking Features",
                        "• GitHub repository releases\n" +
                                "• Push events and commits\n" +
                                "• Issue and pull request comments\n" +
                                "• Package version releases", false)
                .addField("📝 Examples",
                        "`/github track repo microsoft/vscode`\n" +
                                "`/github track package react`\n" +
                                "`/github check repo torvalds/linux`", false)
                .addField("💡 Tips",
                        "• Repository names must be in `owner/repository` format\n" +
                                "• All notifications will be sent to the channel where tracking was enabled\n" +
                                "• Use `/github list` to see what's currently being tracked", false);

        event.getHook().editOriginalEmbeds(embed.build()).queue();
    }

    private boolean isValidRepoFormat(String repoName) {
        return repoName != null && repoName.matches("^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$");
    }

    // Helper classes for storing tracked items
    public static class TrackedRepository {
        final String name;
        final String guildId;
        final String channelId;
        final long addedTimestamp;

        TrackedRepository(String name, String guildId, String channelId) {
            this.name = name;
            this.guildId = guildId;
            this.channelId = channelId;
            this.addedTimestamp = System.currentTimeMillis();
        }
    }

    public static class TrackedPackage {
        final String name;
        final String guildId;
        final String channelId;
        final long addedTimestamp;

        TrackedPackage(String name, String guildId, String channelId) {
            this.name = name;
            this.guildId = guildId;
            this.channelId = channelId;
            this.addedTimestamp = System.currentTimeMillis();
        }
    }
}
