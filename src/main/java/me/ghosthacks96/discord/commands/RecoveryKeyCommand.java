package me.ghosthacks96.discord.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class RecoveryKeyCommand extends ListenerAdapter {

    private static final String API_URL = "https://ghosthacks96.me/API.php";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private final HttpClient httpClient;
    private final Gson gson;

    public RecoveryKeyCommand() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    public static CommandData getCommandData() {
        return Commands.slash("recovery", "Request a recovery key for your account")
                .addOption(OptionType.STRING, "email", "Your email address", true);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("recovery")) {
            return;
        }

        // Defer reply to prevent timeout
        event.deferReply(true).queue(); // true = ephemeral (only visible to user)

        String email = event.getOption("email").getAsString().trim();

        // Validate email format
        if (!isValidEmail(email)) {
            event.getHook().editOriginal("❌ **Invalid Email Format**\n" +
                    "Please provide a valid email address.").queue();
            return;
        }

        // Generate UUID for the recovery key
        String recoveryUuid = UUID.randomUUID().toString();

        try {
            // Create request to your API
            boolean success = requestRecoveryKey(email, recoveryUuid, event.getUser().getId());

            if (success) {
                // Success response
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("✅ Recovery Key Generated")
                        .setDescription("A recovery key has been generated for your account.")
                        .addField("Email", email, false)
                        .addField("Recovery Key", "`" + recoveryUuid + "`", false)
                        .addField("⚠️ Important",
                                "• Save this key in a secure location\n" +
                                        "• This key can only be used once\n" +
                                        "• Keep it confidential and don't share it", false)
                        .setColor(Color.GREEN)
                        .setFooter("Recovery Key System", event.getJDA().getSelfUser().getAvatarUrl());

                event.getHook().editOriginalEmbeds(embed.build()).queue();

                // Log the request (optional)
                System.out.println("Recovery key generated for user " + event.getUser().getId() +
                        " with email: " + email + " | UUID: " + recoveryUuid);

            } else {
                event.getHook().editOriginal("❌ **Failed to Generate Recovery Key**\n" +
                        "There was an error processing your request. Please try again later or contact an administrator.").queue();
            }

        } catch (Exception e) {
            System.err.println("Error in recovery key command: " + e.getMessage());
            e.printStackTrace();

            event.getHook().editOriginal("❌ **System Error**\n" +
                    "An unexpected error occurred. Please try again later.").queue();
        }
    }

    private boolean requestRecoveryKey(String email, String uuid, String discordUserId) {
        try {
            // Create JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("action", "recovery_request");
            payload.addProperty("email", email);
            payload.addProperty("uuid", uuid);
            payload.addProperty("discord_user_id", discordUserId);

            // Create HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "GhostBot-Discord/1.0")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            // Send request
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // Check response
            if (response.statusCode() == 200) {
                JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
                return responseJson.has("success") && responseJson.get("success").getAsBoolean();
            } else {
                System.err.println("API returned status code: " + response.statusCode());
                System.err.println("Response body: " + response.body());
                return false;
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Network error when requesting recovery key: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Unexpected error when requesting recovery key: " + e.getMessage());
            return false;
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }
}