package me.ghosthacks96.discord.events;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.*;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.role.RoleCreateEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdateNameEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.awt.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AuditLogListener extends ListenerAdapter {

    private static final String AUDIT_CHANNEL_NAME = "audit-logs";
    private static final String AUDIT_CATEGORY_NAME = "Logs";

    // Colors for different types of events
    private static final Color COLOR_COMMAND = new Color(0x3498db);      // Blue
    private static final Color COLOR_MEMBER = new Color(0x2ecc71);       // Green
    private static final Color COLOR_CHANNEL = new Color(0xe67e22);      // Orange
    private static final Color COLOR_ROLE = new Color(0x9b59b6);         // Purple
    private static final Color COLOR_DELETE = new Color(0xe74c3c);       // Red
    private static final Color COLOR_MODERATION = new Color(0xf39c12);   // Yellow

    // =================== COMMAND LOGGING ===================

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // Skip ticket-related commands
        if (isTicketCommand(event.getName())) {
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🔧 Command Executed")
                .setColor(COLOR_COMMAND)
                .addField("Command", "`/" + event.getName() + "`", true)
                .addField("User", event.getUser().getAsMention() + " (" + event.getUser().getAsTag() + ")", true)
                .addField("Channel", event.getChannel().getAsMention(), true)
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        // Add command options if any
        if (!event.getOptions().isEmpty()) {
            StringBuilder options = new StringBuilder();
            event.getOptions().forEach(option ->
                    options.append("`").append(option.getName()).append(":").append(option.getAsString()).append("` "));
            embed.addField("Options", options.toString().trim(), false);
        }

        sendAuditLog(event.getGuild(), embed.build());
    }

    // =================== MEMBER EVENTS ===================

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📥 Member Joined")
                .setColor(COLOR_MEMBER)
                .addField("User", event.getUser().getAsMention() + " (" + event.getUser().getAsTag() + ")", true)
                .addField("Account Created", "<t:" + event.getUser().getTimeCreated().toEpochSecond() + ":R>", true)
                .addField("Member Count", String.valueOf(event.getGuild().getMemberCount()), true)
                .setThumbnail(event.getUser().getAvatarUrl())
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📤 Member Left")
                .setColor(COLOR_DELETE)
                .addField("User", event.getUser().getAsTag(), true)
                .addField("Member Count", String.valueOf(event.getGuild().getMemberCount()), true)
                .setThumbnail(event.getUser().getAvatarUrl())
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onGuildMemberUpdateNickname(GuildMemberUpdateNicknameEvent event) {
        String oldNick = event.getOldNickname() != null ? event.getOldNickname() : "None";
        String newNick = event.getNewNickname() != null ? event.getNewNickname() : "None";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("✏️ Nickname Changed")
                .setColor(COLOR_MEMBER)
                .addField("User", event.getUser().getAsMention() + " (" + event.getUser().getAsTag() + ")", true)
                .addField("Old Nickname", "`" + oldNick + "`", true)
                .addField("New Nickname", "`" + newNick + "`", true)
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onUserUpdateName(UserUpdateNameEvent event) {
        // Check if user is in any mutual guilds and log for each
        event.getJDA().getGuilds().forEach(guild -> {
            Member member = guild.getMemberById(event.getUser().getId());
            if (member != null) {
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("🏷️ Username Changed")
                        .setColor(COLOR_MEMBER)
                        .addField("User", event.getUser().getAsMention(), true)
                        .addField("Old Username", "`" + event.getOldName() + "`", true)
                        .addField("New Username", "`" + event.getNewName() + "`", true)
                        .setTimestamp(Instant.now())
                        .setFooter("User ID: " + event.getUser().getId());

                sendAuditLog(guild, embed.build());
            }
        });
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        StringBuilder roles = new StringBuilder();
        event.getRoles().forEach(role -> roles.append(role.getAsMention()).append(" "));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("➕ Role Added")
                .setColor(COLOR_ROLE)
                .addField("User", event.getUser().getAsMention() + " (" + event.getUser().getAsTag() + ")", true)
                .addField("Roles Added", roles.toString().trim(), false)
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        StringBuilder roles = new StringBuilder();
        event.getRoles().forEach(role -> roles.append(role.getAsMention()).append(" "));

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("➖ Role Removed")
                .setColor(COLOR_DELETE)
                .addField("User", event.getUser().getAsMention() + " (" + event.getUser().getAsTag() + ")", true)
                .addField("Roles Removed", roles.toString().trim(), false)
                .setTimestamp(Instant.now())
                .setFooter("User ID: " + event.getUser().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    // =================== CHANNEL EVENTS ===================

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        // Skip ticket channels
        if (isTicketChannel(event.getChannel().getName())) {
            return;
        }

        String categoryName = "None";
        if (event.getChannel().getType() == ChannelType.TEXT) {
            TextChannel textChannel = event.getChannel().asTextChannel();
            if (textChannel.getParentCategory() != null) {
                categoryName = textChannel.getParentCategory().getName();
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📝 Channel Created")
                .setColor(COLOR_CHANNEL)
                .addField("Channel", event.getChannel().getAsMention(), true)
                .addField("Type", event.getChannel().getType().name(), true)
                .addField("Category", categoryName, true)
                .setTimestamp(Instant.now())
                .setFooter("Channel ID: " + event.getChannel().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        // Skip ticket channels
        if (isTicketChannel(event.getChannel().getName())) {
            return;
        }

        String categoryName = "None";
        if (event.getChannel().getType() == ChannelType.TEXT) {
            TextChannel textChannel = event.getChannel().asTextChannel();
            if (textChannel.getParentCategory() != null) {
                categoryName = textChannel.getParentCategory().getName();
            }
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🗑️ Channel Deleted")
                .setColor(COLOR_DELETE)
                .addField("Channel Name", "`#" + event.getChannel().getName() + "`", true)
                .addField("Type", event.getChannel().getType().name(), true)
                .addField("Category", categoryName, true)
                .setTimestamp(Instant.now())
                .setFooter("Channel ID: " + event.getChannel().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onChannelUpdateName(ChannelUpdateNameEvent event) {
        // Skip ticket channels
        if (isTicketChannel(event.getOldValue()) || isTicketChannel(event.getNewValue())) {
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("✏️ Channel Name Updated")
                .setColor(COLOR_CHANNEL)
                .addField("Channel", event.getChannel().getAsMention(), true)
                .addField("Old Name", "`#" + event.getOldValue() + "`", true)
                .addField("New Name", "`#" + event.getNewValue() + "`", true)
                .setTimestamp(Instant.now())
                .setFooter("Channel ID: " + event.getChannel().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onChannelUpdateTopic(ChannelUpdateTopicEvent event) {
        // Skip ticket channels
        if (isTicketChannel(event.getChannel().getName())) {
            return;
        }

        String oldTopic = event.getOldValue() != null ? event.getOldValue() : "None";
        String newTopic = event.getNewValue() != null ? event.getNewValue() : "None";

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📋 Channel Topic Updated")
                .setColor(COLOR_CHANNEL)
                .addField("Channel", event.getChannel().getAsMention(), true)
                .addField("Old Topic", oldTopic.length() > 100 ? oldTopic.substring(0, 100) + "..." : oldTopic, false)
                .addField("New Topic", newTopic.length() > 100 ? newTopic.substring(0, 100) + "..." : newTopic, false)
                .setTimestamp(Instant.now())
                .setFooter("Channel ID: " + event.getChannel().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    // =================== ROLE EVENTS ===================

    @Override
    public void onRoleCreate(RoleCreateEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🎭 Role Created")
                .setColor(COLOR_ROLE)
                .addField("Role", event.getRole().getAsMention(), true)
                .addField("Color", event.getRole().getColor() != null ?
                        "#" + Integer.toHexString(event.getRole().getColor().getRGB()).substring(2).toUpperCase() : "Default", true)
                .addField("Hoisted", event.getRole().isHoisted() ? "Yes" : "No", true)
                .setTimestamp(Instant.now())
                .setFooter("Role ID: " + event.getRole().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onRoleDelete(RoleDeleteEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🗑️ Role Deleted")
                .setColor(COLOR_DELETE)
                .addField("Role Name", "`@" + event.getRole().getName() + "`", true)
                .addField("Color", event.getRole().getColor() != null ?
                        "#" + Integer.toHexString(event.getRole().getColor().getRGB()).substring(2).toUpperCase() : "Default", true)
                .setTimestamp(Instant.now())
                .setFooter("Role ID: " + event.getRole().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onRoleUpdateName(RoleUpdateNameEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("✏️ Role Name Updated")
                .setColor(COLOR_ROLE)
                .addField("Role", event.getRole().getAsMention(), true)
                .addField("Old Name", "`@" + event.getOldValue() + "`", true)
                .addField("New Name", "`@" + event.getNewValue() + "`", true)
                .setTimestamp(Instant.now())
                .setFooter("Role ID: " + event.getRole().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    @Override
    public void onRoleUpdatePermissions(RoleUpdatePermissionsEvent event) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🔐 Role Permissions Updated")
                .setColor(COLOR_ROLE)
                .addField("Role", event.getRole().getAsMention(), true)
                .addField("Permission Changes", "Use Discord's Audit Log for detailed permission changes", false)
                .setTimestamp(Instant.now())
                .setFooter("Role ID: " + event.getRole().getId());

        sendAuditLog(event.getGuild(), embed.build());
    }

    // =================== MESSAGE EVENTS ===================

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        // Skip if in ticket channels or audit channel
        if (isTicketChannel(event.getChannel().getName()) ||
                event.getChannel().getName().equals(AUDIT_CHANNEL_NAME)) {
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🗑️ Message Deleted")
                .setColor(COLOR_DELETE)
                .addField("Channel", event.getChannel().getAsMention(), true)
                .addField("Message ID", event.getMessageId(), true)
                .setTimestamp(Instant.now())
                .setFooter("Use Discord's Audit Log for more details");

        sendAuditLog(event.getGuild(), embed.build());
    }

    // =================== UTILITY METHODS ===================

    private boolean isTicketCommand(String commandName) {
        return commandName.equals("ticket") || commandName.equals("close-ticket");
    }

    private boolean isTicketChannel(String channelName) {
        return channelName.startsWith("ticket-");
    }

    private void sendAuditLog(Guild guild, MessageEmbed embed) {
        findOrCreateAuditChannel(guild)
                .thenAccept(channel -> {
                    if (channel != null) {
                        channel.sendMessageEmbeds(embed).queue();
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("Failed to send audit log: " + throwable.getMessage());
                    return null;
                });
    }

    private CompletableFuture<TextChannel> findOrCreateAuditChannel(Guild guild) {
        // Try to find existing audit channel
        List<TextChannel> auditChannels = guild.getTextChannelsByName(AUDIT_CHANNEL_NAME, true);
        if (!auditChannels.isEmpty()) {
            return CompletableFuture.completedFuture(auditChannels.get(0));
        }

        // Find or create logs category
        return findOrCreateLogsCategory(guild)
                .thenCompose(category -> {
                    // Create audit channel
                    return category.createTextChannel(AUDIT_CHANNEL_NAME)
                            .addPermissionOverride(guild.getPublicRole(),
                                    null,
                                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND))
                            .addPermissionOverride(guild.getBotRole(),
                                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                            Permission.MESSAGE_HISTORY, Permission.MESSAGE_EMBED_LINKS),
                                    null)
                            .setTopic("Automated audit log for server events and commands")
                            .submit();
                })
                .thenApply(channel -> {
                    System.out.println("Created audit log channel: " + channel.getName());

                    // Send initial message
                    EmbedBuilder welcome = new EmbedBuilder()
                            .setTitle("🔍 Audit Log System Initialized")
                            .setDescription("This channel will automatically log server events and command usage.")
                            .addField("What is logged:",
                                    "• Slash commands (excluding ticket system)\n" +
                                            "• Member joins/leaves\n" +
                                            "• Nickname and username changes\n" +
                                            "• Role additions/removals\n" +
                                            "• Channel creation/deletion/edits\n" +
                                            "• Role creation/deletion/edits\n" +
                                            "• Message deletions", false)
                            .setColor(COLOR_COMMAND)
                            .setTimestamp(Instant.now());

                    channel.sendMessageEmbeds(welcome.build()).queue();
                    return channel;
                });
    }

    private CompletableFuture<Category> findOrCreateLogsCategory(Guild guild) {
        // Try to find existing logs category
        List<Category> categories = guild.getCategoriesByName(AUDIT_CATEGORY_NAME, true);
        if (!categories.isEmpty()) {
            return CompletableFuture.completedFuture(categories.get(0));
        }

        // Create logs category
        return guild.createCategory(AUDIT_CATEGORY_NAME)
                .submit()
                .thenApply(category -> {
                    System.out.println("Created audit logs category: " + category.getName());
                    return category;
                });
    }
}