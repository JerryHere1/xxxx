package ma.darija.voicebot;

import ma.darija.voicebot.discord.DiscordBot;
import ma.darija.voicebot.discord.VoiceSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Logger;

public final class DarijaVoicePlugin extends JavaPlugin implements DiscordBot.Host, TabCompleter {

    private static final int REQUIRED_JAVA = 25;
    private volatile DiscordBot bot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("darijabot");
        if (cmd != null) cmd.setTabCompleter(this);
        startBot();
    }

    @Override
    public void onDisable() {
        stopBot();
    }

    private void startBot() {
        reloadConfig();
        BotConfig cfg = BotConfig.from(getConfig());

        int java = Runtime.version().feature();
        if (java < REQUIRED_JAVA) {
            getLogger().severe("==========================================================");
            getLogger().severe(" Discord voice needs Java " + REQUIRED_JAVA + " (voice encryption library).");
            getLogger().severe(" This server runs Java " + java + ". Change the Java version in your");
            getLogger().severe(" hosting panel (Startup / Docker image -> Java 25) and restart.");
            getLogger().severe("==========================================================");
            return;
        }
        List<String> problems = cfg.problems();
        if (!problems.isEmpty()) {
            getLogger().warning("DarijaVoiceBot is not started. Fix plugins/DarijaVoiceBot/config.yml then run /darijabot reload:");
            problems.forEach(p -> getLogger().warning(" - " + p));
            return;
        }

        DiscordBot newBot = new DiscordBot(cfg, this);
        bot = newBot;
        // Never block the Minecraft main thread
        Thread t = new Thread(() -> {
            try {
                newBot.start();
            } catch (Throwable e) {
                getLogger().severe("Could not start the Discord bot: " + e);
                if (e instanceof UnsupportedClassVersionError || e instanceof UnsatisfiedLinkError) {
                    getLogger().severe("This usually means the wrong Java version or an unsupported CPU (needs Java 25 on x86-64/ARM64).");
                }
            }
        }, "DarijaBot-Start");
        t.setDaemon(true);
        t.start();
    }

    private void stopBot() {
        DiscordBot b = bot;
        bot = null;
        if (b != null) {
            try {
                b.shutdown();
            } catch (Throwable e) {
                getLogger().warning("Error while stopping bot: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ DiscordBot.Host

    @Override
    public Logger logger() {
        return getLogger();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void broadcastToMinecraft(String message) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcastMessage(message));
    }

    // ------------------------------------------------------------------ /darijabot

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6DarijaVoiceBot §7- /" + label + " <status|reload|say <text>|ask <question>|reset|leave>");
            return true;
        }
        String sub = args[0].toLowerCase();
        String rest = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        DiscordBot b = bot;
        switch (sub) {
            case "status" -> sender.sendMessage("§6DarijaVoiceBot: §f" + (b == null ? "not running (check console)" : b.statusText())
                    + " §7| Java " + Runtime.version().feature());
            case "reload" -> {
                sender.sendMessage("§eRestarting the Discord bot...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    stopBot();
                    Bukkit.getScheduler().runTask(this, () -> {
                        startBot();
                        sender.sendMessage("§aDone. Check the console for errors.");
                    });
                });
            }
            case "leave" -> {
                if (b != null) b.leaveAll();
                sender.sendMessage("§aLeft all voice channels.");
            }
            case "reset" -> {
                VoiceSession s = b == null ? null : b.anySession();
                if (s != null) s.resetMemory();
                sender.sendMessage("§aMemory cleared.");
            }
            case "say", "ask" -> {
                if (rest.isEmpty()) {
                    sender.sendMessage("§cWrite some text after /" + label + " " + sub);
                    return true;
                }
                VoiceSession s = b == null ? null : b.anySession();
                if (s == null) {
                    sender.sendMessage("§cThe bot is not in a voice channel. Use /join in Discord first.");
                    return true;
                }
                if (sub.equals("say")) {
                    s.say(rest, err -> tell(sender, "§cError: " + err));
                    sender.sendMessage("§a🔊 Saying it in Discord.");
                } else {
                    sender.sendMessage("§7Thinking...");
                    s.ask(sender.getName(), rest,
                            answer -> tell(sender, "§a[Bot] §f" + answer),
                            err -> tell(sender, "§cError: " + err));
                }
            }
            default -> sender.sendMessage("§cUnknown sub-command. Use status, reload, say, ask, reset or leave.");
        }
        return true;
    }

    private void tell(CommandSender sender, String msg) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(msg));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "reload", "say", "ask", "reset", "leave").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
