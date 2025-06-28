package me.ghosthacks96.discord.commands;

import me.ghosthacks96.discord.GhostBot;
import me.ghosthacks96.discord.configs.Config;
import me.ghosthacks96.discord.configs.ConfigManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class RecoveryKeyCommand extends ListenerAdapter {

    private static final String API_URL = "https://ghosthacks96.me/site/GhostAPI/API.php";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private final Gson gson;

    public RecoveryKeyCommand() {
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
                // Create recovery file content
                String fileContent = createRecoveryFileContent(email, recoveryUuid, event.getUser().getId());

                // Create file upload
                ByteArrayInputStream fileStream = new ByteArrayInputStream(fileContent.getBytes());
                FileUpload fileUpload = FileUpload.fromData(fileStream, "rt.txt");

                // Success response with file
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("✅ Recovery Key Generated")
                        .setDescription("Your recovery key has been generated and is attached as a file.")
                        .addField("Email", email, false)
                        .addField("📁 File Instructions",
                                "• Download the attached `rt.txt` file\n" +
                                        "• Save it to: `%APPDATA%\\ghosthacks96\\GhostSecure\\rt.txt`\n" +
                                        "• Should be in the same as the programs other needed files!\n" , false)
                        .addField("Notice"," If you save this file in the correct location and launch the app. but it failes to enter recovery mode. Create a ticket...",false)
                        .setColor(Color.GREEN)
                        .setFooter("Recovery Key System", event.getJDA().getSelfUser().getAvatarUrl());

                event.getHook().editOriginalEmbeds(embed.build())
                        .setFiles(fileUpload)
                        .queue();

                // Log the request (optional)
                System.out.println("Recovery key file generated for user " + event.getUser().getId() +
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

    private String createRecoveryFileContent(String email, String uuid, String discordUserId) {
        return uuid;
    }

    private boolean requestRecoveryKey(String email, String uuid, String discordUserId) {
        try {
            // Create JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("action", "recovery_request");
            payload.addProperty("email", email);
            payload.addProperty("uuid", uuid);
            payload.addProperty("discord_user_id", discordUserId);

            // Setup HttpURLConnection
            java.net.URL url = new java.net.URL(API_URL);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "GhostBot-Discord/1.0");
            conn.setConnectTimeout(10000); // 10 seconds
            conn.setReadTimeout(30000); // 30 seconds
            conn.setDoOutput(true);

            // Write JSON payload
            try (java.io.OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Read response
            int status = conn.getResponseCode();
            java.io.InputStream is = (status == 200) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line.trim());
                }
            }
            conn.disconnect();

            if (status == 200) {
                JsonObject responseJson = gson.fromJson(response.toString(), JsonObject.class);
                return responseJson.has("success") && responseJson.get("success").getAsBoolean();
            } else {
                System.err.println("API returned status code: " + status);
                System.err.println("Response body: " + response);
                return false;
            }
        } catch (IOException e) {
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