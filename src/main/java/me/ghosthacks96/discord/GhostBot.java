package me.ghosthacks96.discord;

import me.ghosthacks96.discord.commands.RecoveryKeyCommand;
import me.ghosthacks96.discord.commands.TicketCloseCommand;
import me.ghosthacks96.discord.commands.TicketCommand;
import me.ghosthacks96.discord.configs.Config;
import me.ghosthacks96.discord.configs.ConfigManager;
import me.ghosthacks96.discord.events.AntiSpamListener;
import me.ghosthacks96.discord.events.AuditLogListener;
import me.ghosthacks96.discord.services.GitHubPollingService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.File;
import java.util.List;
import java.util.Set;

public class GhostBot {

    private static String DISCORD_TOKEN;
    private JDA jda;
    private AntiSpamListener antiSpamListener;
    private RecoveryKeyCommand recoveryKeyCommand;
    private TicketCommand ticketCommand;
    private TicketCloseCommand ticketCloseCommand;
    public AuditLogListener auditLogListener;

    public static String GITHUB_TOKEN;
    public Set<GitHubPollingService> repoPollingServices;

    public static final Object lock = new Object();
    public static GhostBot instance;
    public static ConfigManager configManager;

    public Config mainConfig;

    public static GhostBot getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new GhostBot();
                }
            }
        }
        return instance;
    }

    public static void main(String[] args) {
        getInstance().start();
    }

    public void start() {
        try {
            // Start async console thread for shutdown
            Thread consoleThread = new Thread(() -> {
                java.util.Scanner scanner = new java.util.Scanner(System.in);
                while (true) {
                    String input = scanner.nextLine();
                    if (input.equalsIgnoreCase("stop") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                        System.out.println("Shutting down bot...");
                        if (jda != null) {
                            jda.shutdown();
                        }
                        System.exit(0);
                    }
                }
            });
            consoleThread.setDaemon(true);
            consoleThread.start();

            configManager = ConfigManager.getInstance();


            // Ensure configs directory exists
            File configsDir = new File("configs");
            if (!configsDir.exists()) {
                configsDir.mkdirs();
            }

            // Check for configs/config.yml and register if missing
            File mainConfigFile = new File("configs/config.yml");
            if (!mainConfigFile.exists()) {
                // Create a default config and register it
                List<String> defaultConfigLines = List.of(
                        "discord_token: YOUR_DISCORD_BOT_TOKEN",
                        "github_token: YOUR_GITHUB_TOKEN",
                        "anti_spam_enabled: true",
                        "audit_log_enabled: true",
                        "ticket_system_enabled: true"
                );
                java.nio.file.Files.write(mainConfigFile.toPath(), defaultConfigLines);
            }
            // Register the config with the manager
            configManager.registerFileConfig("main", "configs/config.yml");
            mainConfig = configManager.getConfig("main");



            if(mainConfig.getString("discord_token").equals("YOUR_DISCORD_BOT_TOKEN") ||
               mainConfig.getString("github_token").equals("YOUR_GITHUB_TOKEN")) {
                System.err.println("Please configure your config.yml with valid tokens and repositories.");

                System.exit(1);
            }




            // Initialize components based on config
            if (mainConfig.getBoolean("anti_spam_enabled", true)) {
                antiSpamListener = new AntiSpamListener();
            } else {
                antiSpamListener = null;
            }
            if (mainConfig.getBoolean("ticket_system_enabled", true)) {
                ticketCommand = new TicketCommand();
                ticketCloseCommand = new TicketCloseCommand();
            } else {
                ticketCommand = null;
                ticketCloseCommand = null;
            }
            if( mainConfig.getBoolean("audit_log_enabled", true)) {
                auditLogListener = new AuditLogListener();
            } else {
                auditLogListener = null;
            }


            // Always initialize recoveryKeyCommand (or add config if needed)
            recoveryKeyCommand = new RecoveryKeyCommand();

            // Build JDA instance with only enabled listeners
            JDABuilder builder = JDABuilder.createDefault(mainConfig.getString("discord_token"))
                    .enableIntents(GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS));
            if (antiSpamListener != null) builder.addEventListeners(antiSpamListener);
            builder.addEventListeners(recoveryKeyCommand);
            if (ticketCommand != null) builder.addEventListeners(ticketCommand);
            if (ticketCloseCommand != null) builder.addEventListeners(ticketCloseCommand);
            if( auditLogListener != null) builder.addEventListeners(auditLogListener);
            jda = builder.build();

            // Wait for bot to be ready
            jda.awaitReady();

            // Register slash commands
            registerCommands();

            System.out.println("GhostBot is ready!");



            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        } catch (Exception e) {
            System.err.println("Failed to start bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void registerCommands() {
        try {
            // Register commands globally (takes up to 1 hour to propagate)
            jda.updateCommands()
                    .addCommands(
                            RecoveryKeyCommand.getCommandData(),
                            TicketCommand.getCommandData(),
                            TicketCloseCommand.getCommandData()
                    )
                    .queue(
                            success -> System.out.println("Successfully registered commands!"),
                            error -> System.err.println("Failed to register commands: " + error.getMessage())
                    );
        } catch (Exception e) {
            System.err.println("Error registering commands: " + e.getMessage());
        }
    }

    private void shutdown() {
        System.out.println("Shutting down GhostBot...");

        if (antiSpamListener != null) {
            antiSpamListener.shutdown();
        }

        if (jda != null) {
            jda.shutdown();
        }

        System.out.println("GhostBot shutdown complete.");
    }
}