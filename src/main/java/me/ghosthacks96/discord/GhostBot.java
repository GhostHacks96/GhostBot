package me.ghosthacks96.discord;

import me.ghosthacks96.discord.commands.GitHubTrackCommand;
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
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GhostBot {

    private static String DISCORD_TOKEN;
    private JDA jda;
    private AntiSpamListener antiSpamListener;
    private RecoveryKeyCommand recoveryKeyCommand;
    private TicketCommand ticketCommand;
    private TicketCloseCommand ticketCloseCommand;
    public AuditLogListener auditLogListener;
    public GitHubTrackCommand githubCommand;

    public static String GITHUB_TOKEN;
    public Map<String,GitHubPollingService> repoPollingServices;

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

            // Check for configs/github_repos.yml and register if missing
            File githubReposFile = new File("configs/github_repos.yml");
            if (!githubReposFile.exists()) {
                // Example: create a default YAML with one owner and repo objects (name & channel_id)
                List<String> defaultGithubRepos = List.of(
                        "ghosthacks96:",
                        "  - name: test",
                        "    channel_id: '1234567890'",
                        "  - name: test1",
                        "    channel_id: '0987654321'"
                );
                java.nio.file.Files.write(githubReposFile.toPath(), defaultGithubRepos);
            }
            configManager.registerFileConfig("github_repos", "configs/github_repos.yml");


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
            githubCommand = new GitHubTrackCommand();

            GITHUB_TOKEN = mainConfig.getString("github_token");

            // Initialize repoPollingServices map before use
            if (repoPollingServices == null) {
                repoPollingServices = new java.util.HashMap<>();
            }

            // Build JDA instance with only enabled listeners
            JDABuilder builder = JDABuilder.createDefault(mainConfig.getString("discord_token"))
                    .enableIntents(GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS));
            if (antiSpamListener != null) builder.addEventListeners(antiSpamListener);
            builder.addEventListeners(recoveryKeyCommand);
            builder.addEventListeners(githubCommand);
            if (ticketCommand != null) builder.addEventListeners(ticketCommand);
            if (ticketCloseCommand != null) builder.addEventListeners(ticketCloseCommand);
            if( auditLogListener != null) builder.addEventListeners(auditLogListener);
            jda = builder.build();

            // Wait for bot to be ready
            jda.awaitReady();

            // Register slash commands
            registerCommands();

            System.out.println("GhostBot is ready!");

            Config githubReposConfig = configManager.getConfig("github_repos");
            githubReposConfig.load();

            // Collect all repos to track from githubReposConfig
            List<String> reposToTrack = new java.util.ArrayList<>();
            if (githubReposConfig != null) {
                for (String owner : githubReposConfig.getData().keySet()) {
                    Object repoListObj = githubReposConfig.get(owner);
                    if (repoListObj instanceof List<?> repoList) {
                        for (Object repoObj : repoList) {
                            if (repoObj instanceof Map<?,?> repoMap) {
                                String repoName = (String) repoMap.get("name");
                                String channelId = (String) repoMap.get("channel_id");
                                // Ignore default/test values
                                if (repoName != null && channelId != null &&
                                    !repoName.startsWith("test") && !channelId.equals("1234567890") && !channelId.equals("0987654321")) {
                                    reposToTrack.add(owner + "/" + repoName+":"+ channelId);
                                    // Optionally: store channelId mapping if needed
                                }
                            }
                        }
                    }
                }
            }
            if(reposToTrack.isEmpty()) {
                System.out.println("No valid GitHub repositories found to track. Please configure your github_repos.yml file.");
            } else {
                System.out.println("Found " + reposToTrack.size() + " repositories to track:");
                for (String repo : reposToTrack) {
                    String[] parts = repo.split(":");
                    if (parts.length == 2) {
                        String repository = parts[0];
                        String channelId = parts[1];
                        addRepositoryPolling(channelId, repository);
                    } else {
                        System.err.println("Invalid repository format: " + repo);
                    }
                }
            }


            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        } catch (Exception e) {
            System.err.println("Failed to start bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void registerCommands() {
        try {
            //jda.updateCommands().queue();
            // Get all guilds the bot is in
            List<Guild> guilds = jda.getGuilds();

            if (guilds.isEmpty()) {
                System.out.println("Bot is not in any guilds. Commands will not be registered.");
                return;
            }

            System.out.println("Registering commands to " + guilds.size() + " guild(s)...");

            // Register commands to each guild individually
            for (Guild guild : guilds) {
                guild.updateCommands()
                        .addCommands(
                                RecoveryKeyCommand.getCommandData(),
                                TicketCommand.getCommandData(),
                                TicketCloseCommand.getCommandData(),
                                GitHubTrackCommand.getCommandData()
                        )
                        .queue(
                                success -> System.out.println("Successfully registered commands to guild: " + guild.getName() + " (" + guild.getId() + ")"),
                                error -> System.err.println("Failed to register commands to guild " + guild.getName() + ": " + error.getMessage())
                        );
            }

        } catch (Exception e) {
            System.err.println("Error registering commands: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * Add a new repository to be polled
     * @param channelId The Discord channel ID to send notifications to
     * @param repository The repository in format "owner/repo"
     */
    public void addRepositoryPolling(String channelId, String repository) {
        try {
            if (GITHUB_TOKEN == null || GITHUB_TOKEN.equals("YOUR_GITHUB_TOKEN")) {
                throw new IllegalStateException("GitHub token not configured");
            }
            boolean found = false;

            for(String repo : repoPollingServices.keySet()) {
                if(repo.equals(repository)) {
                    found = true;
                    break;
                }
            }

            if(!found) {
                int pollingInterval = 5;
                GitHubPollingService pollingService = new GitHubPollingService(jda, GITHUB_TOKEN,repository, channelId);
                pollingService.startPolling();
                repoPollingServices.put(repository, pollingService);
            } else {
                System.out.println("Repository polling already exists for: " + repository);
            }

            System.out.println("Added repository polling: " + repository + " -> Channel: " + channelId);
        } catch (Exception e) {
            System.err.println("Failed to add repository polling for " + repository + ": " + e.getMessage());
            throw new RuntimeException("Failed to add repository polling", e);
        }
    }

    /**
     * Remove all polling services for a specific repository
     * @param repository The repository in format "owner/repo"
     */
    public void removeRepositoryPolling(String repository) {
        Map<String,GitHubPollingService> repoPollingServices_old = repoPollingServices;

        for(String repo : repoPollingServices_old.keySet()) {
            if(repo.equals(repository)) {
                GitHubPollingService service = repoPollingServices_old.get(repo);
                if(service != null) {
                    service.shutdown();
                    repoPollingServices.remove(repo);
                    System.out.println("Removed repository polling for: " + repo);
                    break;
                } else {
                    System.err.println("No polling service found for repository: " + repo);
                }
            }
        }


        System.out.println("Repository polling removal requested for: " + repository);
        System.out.println("Note: Individual repository removal not fully implemented. Consider restarting bot to clear all polling.");
    }



    private void shutdown() {
        System.out.println("Shutting down GhostBot...");
        // Reset GitHub repo polling data to only store currently tracked repos in github_repos config
        try {
            Config githubReposConfig = configManager.getConfig("github_repos");
            if (githubReposConfig != null && repoPollingServices != null) {
                // Clear all owners
                githubReposConfig.getData().clear();
                // Refill with only currently tracked repos
                for (Map.Entry<String, GitHubPollingService> entry : repoPollingServices.entrySet()) {
                    String repoFull = entry.getKey(); // owner/repo
                    GitHubPollingService service = entry.getValue();
                    String[] parts = repoFull.split("/");
                    if (parts.length == 2) {
                        String owner = parts[0];
                        String repo = parts[1];
                        String channelId = service.getChannelId();
                        // Add to config
                        List<Object> ownerRepos = (List<Object>) githubReposConfig.get(owner);
                        if (ownerRepos == null) {
                            ownerRepos = new java.util.ArrayList<>();
                            githubReposConfig.set(owner, ownerRepos);
                        }
                        java.util.Map<String, Object> repoObj = new java.util.HashMap<>();
                        repoObj.put("name", repo);
                        repoObj.put("channel_id", channelId);
                        ownerRepos.add(repoObj);
                    }
                }
                githubReposConfig.save();
            }
        } catch (Exception e) {
            System.err.println("Error updating github_repos config on shutdown: " + e.getMessage());
        }

        // Optionally, persist only tracked repos to config or file here if needed

        if (antiSpamListener != null) {
            antiSpamListener.shutdown();
        }

        if (jda != null) {
            jda.shutdown();
        }

        System.out.println("GhostBot shutdown complete.");
    }
}