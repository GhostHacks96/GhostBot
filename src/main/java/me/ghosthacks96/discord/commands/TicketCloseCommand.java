package me.ghosthacks96.discord.commands;

import me.ghosthacks96.discord.GhostBot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TicketCloseCommand extends ListenerAdapter {

    private static final String TICKET_PREFIX = "ticket-";
    private static final String CLOSED_CATEGORY_NAME = "Closed Tickets";

    public static CommandData getCommandData() {
        return Commands.slash("close", "Close the current ticket")
                .addOption(OptionType.STRING, "reason", "Reason for closing the ticket", false);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("close")) {
            return;
        }

        TextChannel channel = event.getChannel().asTextChannel();

        // Check if this is a ticket channel
        if (!isTicketChannel(channel)) {
            event.reply("❌ This command can only be used in ticket channels.").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        if (member == null) {
            event.reply("❌ Unable to identify user.").setEphemeral(true).queue();
            return;
        }

        // Check permissions (staff or ticket creator)
        if (!canCloseTicket(channel, member)) {
            event.reply("❌ You don't have permission to close this ticket.").setEphemeral(true).queue();
            return;
        }

        String reason = event.getOption("reason") != null ?
                event.getOption("reason").getAsString() : "No reason provided";

        // Confirm closure
        EmbedBuilder confirmEmbed = new EmbedBuilder()
                .setTitle("🔒 Close Ticket Confirmation")
                .setDescription("Are you sure you want to close this ticket?")
                .addField("Closed by", member.getAsMention(), true)
                .addField("Reason", reason, true)
                .addField("⚠️ Warning", "This action cannot be undone easily.", false)
                .setColor(Color.ORANGE);

        Button confirmButton = Button.danger("confirm_close", "✅ Confirm Close");
        Button cancelButton = Button.secondary("cancel_close", "❌ Cancel");

        event.replyEmbeds(confirmEmbed.build())
                .setActionRow(confirmButton, cancelButton)
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();

        switch (buttonId) {
            case "close_ticket" -> handleCloseTicketButton(event);
            case "confirm_close" -> handleConfirmClose(event);
            case "cancel_close" -> handleCancelClose(event);
            case "claim_ticket" -> handleClaimTicket(event);
            case "change_priority" -> handleChangePriority(event);
            // Add handlers for priority buttons
            case "priority_low" -> handlePriorityChange(event, "Low", Color.GREEN);
            case "priority_normal" -> handlePriorityChange(event, "Normal", Color.YELLOW);
            case "priority_high" -> handlePriorityChange(event, "High", Color.RED);
        }
    }

    private void handleCloseTicketButton(ButtonInteractionEvent event) {
        TextChannel channel = event.getChannel().asTextChannel();
        Member member = event.getMember();

        if (!isTicketChannel(channel) || member == null) {
            event.reply("❌ Invalid ticket or user.").setEphemeral(true).queue();
            return;
        }

        if (!canCloseTicket(channel, member)) {
            event.reply("❌ You don't have permission to close this ticket.").setEphemeral(true).queue();
            return;
        }

        // Show close confirmation
        EmbedBuilder confirmEmbed = new EmbedBuilder()
                .setTitle("🔒 Close Ticket Confirmation")
                .setDescription("Are you sure you want to close this ticket?")
                .addField("Closed by", member.getAsMention(), true)
                .addField("⚠️ Warning", "This action will archive the ticket.", false)
                .setColor(Color.ORANGE);

        Button confirmButton = Button.danger("confirm_close", "✅ Confirm Close");
        Button cancelButton = Button.secondary("cancel_close", "❌ Cancel");

        event.replyEmbeds(confirmEmbed.build())
                .setActionRow(confirmButton, cancelButton)
                .setEphemeral(true)
                .queue();
    }

    private void handleConfirmClose(ButtonInteractionEvent event) {
        event.deferEdit().queue();

        TextChannel channel = event.getChannel().asTextChannel();
        Member member = event.getMember();

        if (member == null) return;

        // Get the reason from the original interaction if available
        String reason = "Closed via confirmation";

        closeTicket(channel, member, reason);
    }

    private void handleCancelClose(ButtonInteractionEvent event) {
        event.editMessage("❌ Ticket closure cancelled.")
                .setEmbeds()
                .setComponents()
                .queue();
    }

    private void handleClaimTicket(ButtonInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) return;

        // Check if user is staff
        if (!member.hasPermission(Permission.ADMINISTRATOR) &&
                !member.hasPermission(Permission.MANAGE_CHANNEL)) {
            event.reply("❌ Only staff members can claim tickets.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder claimedEmbed = new EmbedBuilder()
                .setTitle("✋ Ticket Claimed")
                .setDescription(member.getAsMention() + " has claimed this ticket and will handle your request.")
                .setColor(Color.GREEN)
                .setTimestamp(java.time.Instant.now());

        event.replyEmbeds(claimedEmbed.build()).queue();
    }

    private void handleChangePriority(ButtonInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) return;

        // Check if user is staff
        if (!member.hasPermission(Permission.ADMINISTRATOR) &&
                !member.hasPermission(Permission.MANAGE_CHANNEL)) {
            event.reply("❌ Only staff members can change priority.").setEphemeral(true).queue();
            return;
        }

        // Create priority buttons
        Button lowPriority = Button.success("priority_low", "🟢 Low");
        Button normalPriority = Button.secondary("priority_normal", "🟡 Normal");
        Button highPriority = Button.danger("priority_high", "🔴 High");

        event.reply("Select the new priority level:")
                .setActionRow(lowPriority, normalPriority, highPriority)
                .setEphemeral(true)
                .queue();
    }

    private void handlePriorityChange(ButtonInteractionEvent event, String priority, Color color) {
        Member member = event.getMember();
        if (member == null) return;

        // Check if user is staff
        if (!member.hasPermission(Permission.ADMINISTRATOR) &&
                !member.hasPermission(Permission.MANAGE_CHANNEL)) {
            event.reply("❌ Only staff members can change priority.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder priorityEmbed = new EmbedBuilder()
                .setTitle("🏷️ Priority Changed")
                .setDescription("Ticket priority has been updated to **" + priority + "**")
                .addField("Changed by", member.getAsMention(), true)
                .setColor(color)
                .setTimestamp(java.time.Instant.now());

        event.replyEmbeds(priorityEmbed.build()).queue();
    }

    private void closeTicket(TextChannel channel, Member closedBy, String reason) {
        Guild guild = channel.getGuild();

        // Create transcript (simplified version)
        String transcript = createTicketTranscript(channel);

        // Send closure message
        EmbedBuilder closureEmbed = new EmbedBuilder()
                .setTitle("🔒 Ticket Closed")
                .setDescription("This ticket has been closed.")
                .addField("Closed by", closedBy.getAsMention(), true)
                .addField("Closed at",
                        java.time.OffsetDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm")), true)
                .addField("Reason", reason, false)
                .addField("📄 Transcript", "A transcript of this ticket has been saved.", false)
                .setColor(Color.RED)
                .setFooter("Ticket will be deleted in 10 seconds");

        channel.sendMessageEmbeds(closureEmbed.build()).queue();

        // Log the closure
        System.out.println("Ticket closed: " + channel.getName() + " by " +
                closedBy.getEffectiveName() + " - Reason: " + reason);

        // Attempt to DM the ticket creator with the transcript
        Member ticketCreator = getTicketCreator(channel, guild);

        // Create file upload
        ByteArrayInputStream fileStream = new ByteArrayInputStream(transcript.getBytes());
        FileUpload fileUpload = FileUpload.fromData(fileStream, channel.getName()+"-transctipt.txt");
        if (ticketCreator != null) {
            ticketCreator.getUser().openPrivateChannel().queue(privateChannel -> {
                privateChannel.sendMessage("Here is a transcript of your closed ticket: **" + channel.getName() + "**")
                        .addFiles(fileUpload)
                        .queue();
            }, error -> System.err.println("Failed to open DM with ticket creator: " + error.getMessage()));
        }

        // Send transcript to audit log channel
        TextChannel auditLogChannel = GhostBot.getInstance().auditLogListener.findExistingAuditChannel(guild);
        if (auditLogChannel != null) {
            auditLogChannel.sendMessage("Transcript for closed ticket: **" + channel.getName() + "**")
                    .addFiles(fileUpload)
                    .queue();
        }

        // Delete channel after delay
        channel.delete().queueAfter(10, TimeUnit.SECONDS,
                success -> System.out.println("Ticket channel deleted: " + channel.getName()),
                error -> System.err.println("Failed to delete ticket channel: " + error.getMessage())
        );
    }

    // Helper to get the ticket creator from the channel name or history
    private Member getTicketCreator(TextChannel channel, Guild guild) {
        // Try to extract user ID from channel name (if you use IDs in names)
        for (Member member : guild.getMembers()) {
            if (channel.getName().contains(member.getUser().getId())) {
                return member;
            }
        }
        // Fallback: get first non-bot message author
        try {
            List<Message> messages = channel.getHistory().retrievePast(50).complete();
            for (Message msg : messages) {
                if (!msg.getAuthor().isBot()) {
                    return guild.getMember(msg.getAuthor());
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String createTicketTranscript(TextChannel channel) {
        // This is a simplified transcript creation
        // In a production environment, you might want to save this to a file or database
        StringBuilder transcript = new StringBuilder();
        transcript.append("=== TICKET TRANSCRIPT ===\n");
        transcript.append("Channel: ").append(channel.getName()).append("\n");
        transcript.append("Created: ").append(channel.getTimeCreated().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        transcript.append("Closed: ").append(java.time.OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        try {
            List<Message> messages = channel.getHistory().retrievePast(50).complete();
            // Reverse the list to show messages in chronological order
            java.util.Collections.reverse(messages);

            for (Message msg : messages) {
                transcript.append("[").append(msg.getTimeCreated().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append("] ");
                transcript.append(msg.getAuthor().getName()).append(": ");
                transcript.append(msg.getContentDisplay()).append("\n");
            }
        } catch (Exception e) {
            transcript.append("Failed to retrieve message history: ").append(e.getMessage()).append("\n");
            System.err.println("Error creating transcript: " + e.getMessage());
        }

        return transcript.toString();
    }

    private boolean isTicketChannel(TextChannel channel) {
        return channel.getName().startsWith(TICKET_PREFIX);
    }

    private boolean canCloseTicket(TextChannel channel, Member member) {
        // Staff can always close tickets (using MANAGE_CHANNEL instead of NICKNAME_CHANGE)
        if (member.hasPermission(Permission.ADMINISTRATOR) ||
                member.hasPermission(Permission.MANAGE_CHANNEL)) {
            return true;
        }

        // Check if this is the ticket creator
        String channelName = channel.getName();
        if (channelName.startsWith(TICKET_PREFIX)) {
            String userIdentifier = member.getUser().getId();
            return channelName.contains(userIdentifier);
        }

        return false;
    }
}