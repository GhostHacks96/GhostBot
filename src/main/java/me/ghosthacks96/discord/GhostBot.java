package me.ghosthacks96.discord;

import me.ghosthacks96.discord.commands.RecoveryKeyCommand;
import me.ghosthacks96.discord.commands.TicketCloseCommand;
import me.ghosthacks96.discord.commands.TicketCommand;
import me.ghosthacks96.discord.events.AntiSpamListener;
import me.ghosthacks96.discord.events.AuditLogListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class GhostBot {

    private JDA jda;
    private AntiSpamListener antiSpamListener;
    private RecoveryKeyCommand recoveryKeyCommand;
    private TicketCommand ticketCommand;
    private TicketCloseCommand ticketCloseCommand;
    public AuditLogListener auditLogListener;

    public static final Object lock = new Object();
    public static GhostBot instance;

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

            // Initialize components
            antiSpamListener = new AntiSpamListener();
            recoveryKeyCommand = new RecoveryKeyCommand();
            ticketCommand = new TicketCommand();
            ticketCloseCommand = new TicketCloseCommand();

            // Build JDA instance
            jda = JDABuilder.createDefault("")
                    .enableIntents(GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
                    .addEventListeners(antiSpamListener, recoveryKeyCommand, ticketCommand, ticketCloseCommand)
                    .build();

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