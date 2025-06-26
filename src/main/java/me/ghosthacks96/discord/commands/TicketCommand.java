package me.ghosthacks96.discord.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TicketCommand extends ListenerAdapter {

    private static final String TICKET_CATEGORY_NAME = "Support Tickets";
    private static final String TICKET_PREFIX = "ticket-";

    public static CommandData getCommandData() {
        return Commands.slash("ticket", "Create a support ticket")
                .addOption(OptionType.STRING, "reason", "Reason for creating the ticket", true)
                .addOption(OptionType.STRING, "priority", "Priority level of the ticket", false);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("ticket")) {
            return;
        }

        // Defer reply to prevent timeout
        event.deferReply(true).queue();

        Guild guild = event.getGuild();
        Member member = event.getMember();

        if (guild == null || member == null) {
            event.getHook().editOriginal("❌ This command can only be used in servers.").queue();
            return;
        }

        String reason = event.getOption("reason").getAsString();
        String priority = event.getOption("priority") != null ?
                event.getOption("priority").getAsString() : "Normal";
        boolean isPrivate = true;

        // Check if user already has an open ticket
        if (hasOpenTicket(guild, member)) {
            event.getHook().editOriginal("❌ You already have an open ticket. Please use your existing ticket or close it first.").queue();
            return;
        }

        try {
            createTicket(event, guild, member, reason, priority, isPrivate);
        } catch (Exception e) {
            System.err.println("Error creating ticket: " + e.getMessage());
            e.printStackTrace();
            event.getHook().editOriginal("❌ Failed to create ticket. Please try again or contact an administrator.").queue();
        }
    }

    private void createTicket(SlashCommandInteractionEvent event, Guild guild, Member member,
                              String reason, String priority, boolean isPrivate) {

        // Find or create ticket category
        Category ticketCategory = findOrCreateTicketCategory(guild);
        if (ticketCategory == null) {
            event.getHook().editOriginal("❌ Failed to create ticket category. Please ensure the bot has proper permissions.").queue();
            return;
        }

        // Generate ticket name
        String ticketName = TICKET_PREFIX + member.getEffectiveName().toLowerCase()
                .replaceAll("[^a-z0-9]", "") + "-" + System.currentTimeMillis() % 10000;

        // Get staff members (admins + nickname change permission)
        List<Member> staffMembers = getStaffMembers(guild);

        // Create ticket channel
        ticketCategory.createTextChannel(ticketName)
                .queue(channel -> {
                    setupTicketChannel(channel, member, staffMembers, isPrivate);
                    sendTicketWelcomeMessage(channel, member, reason, priority, staffMembers);

                    // Success response to user
                    EmbedBuilder successEmbed = new EmbedBuilder()
                            .setTitle("🎫 Ticket Created Successfully")
                            .setDescription("Your ticket has been created: " + channel.getAsMention())
                            .addField("Reason", reason, false)
                            .addField("Priority", priority, false)
                            .addField("Type", isPrivate ? "Private (Staff Only)" : "Standard", false)
                            .setColor(Color.GREEN)
                            .setFooter("Ticket ID: " + ticketName, member.getEffectiveAvatarUrl());

                    event.getHook().editOriginalEmbeds(successEmbed.build()).queue();

                    // Log ticket creation
                    System.out.println("Ticket created: " + ticketName + " by " + member.getEffectiveName() +
                            " (" + member.getId() + ") - Reason: " + reason);

                }, error -> {
                    System.err.println("Failed to create ticket channel: " + error.getMessage());
                    event.getHook().editOriginal("❌ Failed to create ticket channel. Please contact an administrator.").queue();
                });
    }

    private Category findOrCreateTicketCategory(Guild guild) {
        // Try to find existing category
        List<Category> categories = guild.getCategoriesByName(TICKET_CATEGORY_NAME, true);
        if (!categories.isEmpty()) {
            return categories.get(0);
        }

        // Create category if it doesn't exist
        try {
            return guild.createCategory(TICKET_CATEGORY_NAME)
                    .addPermissionOverride(guild.getPublicRole(), null,
                            EnumSet.of(Permission.VIEW_CHANNEL))
                    .complete();
        } catch (Exception e) {
            System.err.println("Failed to create ticket category: " + e.getMessage());
            return null;
        }
    }

    private List<Member> getStaffMembers(Guild guild) {
        return guild.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .filter(member ->
                        member.hasPermission(Permission.ADMINISTRATOR) ||
                                member.hasPermission(Permission.NICKNAME_CHANGE)
                )
                .collect(Collectors.toList());
    }

    private void setupTicketChannel(TextChannel channel, Member ticketCreator,
                                    List<Member> staffMembers, boolean isPrivate) {

        Guild guild = channel.getGuild();

        // Set permissions for ticket creator
        channel.getManager().putPermissionOverride(ticketCreator,
                EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                        Permission.MESSAGE_HISTORY, Permission.MESSAGE_ADD_REACTION),
                null).queue();

        // Set permissions for staff members
        for (Member staff : staffMembers) {
            channel.getManager().putPermissionOverride(staff,
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ADD_REACTION,
                            Permission.MESSAGE_MANAGE, Permission.MANAGE_CHANNEL),
                    null).queue();
        }

        // Hide from @everyone
        channel.getManager().putPermissionOverride(guild.getPublicRole(),
                null, EnumSet.of(Permission.VIEW_CHANNEL)).queue();

        // If not private, allow other members to view but not send messages
        if (!isPrivate) {
            channel.getManager().putPermissionOverride(guild.getPublicRole(),
                    EnumSet.of(Permission.MESSAGE_HISTORY),
                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)).queue();
        }
    }

    private void sendTicketWelcomeMessage(TextChannel channel, Member ticketCreator,
                                          String reason, String priority, List<Member> staffMembers) {

        // Create staff mentions string
        String staffMentions = staffMembers.stream()
                .map(Member::getAsMention)
                .collect(Collectors.joining(" "));

        // Welcome embed
        EmbedBuilder welcomeEmbed = new EmbedBuilder()
                .setTitle("🎫 Support Ticket")
                .setDescription("Welcome to your support ticket, " + ticketCreator.getAsMention() + "!")
                .addField("📝 Reason", reason, false)
                .addField("⚡ Priority", priority, false)
                .addField("👤 Created by", ticketCreator.getAsMention(), true)
                .addField("📅 Created at",
                        java.time.OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")), true)
                .addField("👥 Staff Notified",
                        staffMembers.size() + " staff member(s) have been notified", false)
                .setColor(Color.BLUE)
                .setThumbnail(ticketCreator.getEffectiveAvatarUrl())
                .setFooter("Use the buttons below to manage this ticket",
                        channel.getJDA().getSelfUser().getAvatarUrl());

        // Create action buttons
        Button closeButton = Button.danger("close_ticket", "🔒 Close Ticket");
        Button claimButton = Button.secondary("claim_ticket", "✋ Claim Ticket");
        Button priorityButton = Button.primary("change_priority", "⚡ Change Priority");

        // Send welcome message
        channel.sendMessage("🚨 **Staff Alert** 🚨\n" + staffMentions +
                        "\n\nA new support ticket has been created and requires your attention!")
                .setEmbeds(welcomeEmbed.build())
                .setActionRow(claimButton, priorityButton, closeButton)
                .queue();

        // Send guidelines message
        EmbedBuilder guidelinesEmbed = new EmbedBuilder()
                .setTitle("📋 Ticket Guidelines")
                .setDescription("Please follow these guidelines for the best support experience:")
                .addField("✅ Do",
                        "• Be clear and detailed in your description\n" +
                                "• Provide relevant screenshots or logs\n" +
                                "• Be patient while waiting for a response\n" +
                                "• Keep the conversation on-topic", false)
                .addField("❌ Don't",
                        "• Spam or repeatedly mention staff\n" +
                                "• Share personal information publicly\n" +
                                "• Use inappropriate language\n" +
                                "• Create multiple tickets for the same issue", false)
                .setColor(Color.ORANGE)
                .setFooter("Staff will respond as soon as possible");

        channel.sendMessageEmbeds(guidelinesEmbed.build())
                .queueAfter(2, TimeUnit.SECONDS);
    }

    private boolean hasOpenTicket(Guild guild, Member member) {
        Category ticketCategory = guild.getCategoriesByName(TICKET_CATEGORY_NAME, true)
                .stream().findFirst().orElse(null);

        if (ticketCategory == null) {
            return false;
        }

        String userIdentifier = member.getEffectiveName().toLowerCase()
                .replaceAll("[^a-z0-9]", "");

        return ticketCategory.getTextChannels().stream()
                .anyMatch(channel -> channel.getName().startsWith(TICKET_PREFIX + userIdentifier));
    }

    /**
     * Get a user-friendly priority color
     */
    public static Color getPriorityColor(String priority) {
        return switch (priority.toLowerCase()) {
            case "high", "urgent", "critical" -> Color.RED;
            case "medium", "normal" -> Color.ORANGE;
            case "low" -> Color.GREEN;
            default -> Color.BLUE;
        };
    }

    /**
     * Get priority emoji
     */
    public static String getPriorityEmoji(String priority) {
        return switch (priority.toLowerCase()) {
            case "high", "urgent", "critical" -> "🔴";
            case "medium", "normal" -> "🟡";
            case "low" -> "🟢";
            default -> "⚪";
        };
    }
}