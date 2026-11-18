package ma.darija.voicebot.discord;

import ma.darija.voicebot.BotConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** The Discord side: login, slash commands, joining/leaving voice. */
public class DiscordBot extends ListenerAdapter {

    /** What the bot needs from the Minecraft plugin. */
    public interface Host {
        Logger logger();

        void broadcastToMinecraft(String message);
    }

    private final BotConfig cfg;
    private final Host host;
    private final Map<Long, VoiceSession> sessions = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public DiscordBot(BotConfig cfg, Host host) {
        this.cfg = cfg;
        this.host = host;
    }

    public Logger log() {
        return host.logger();
    }

    public boolean isOnline() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public String statusText() {
        if (jda == null) return "starting / not connected";
        return jda.getStatus() + ", in voice on " + sessions.size() + " server(s)";
    }

    /** Logs in. Call from a background thread. */
    public void start() throws Exception {
        // Loaded by name so that a wrong Java version gives a clear error instead of a crash.
        DaveSessionFactory dave = (DaveSessionFactory) Class
                .forName("club.minnced.discord.jdave.interop.JDaveSessionFactory")
                .getDeclaredConstructor().newInstance();

        jda = JDABuilder.createLight(cfg.discordToken(), EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                .enableCache(CacheFlag.VOICE_STATE)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(dave))
                .setActivity(Activity.listening("الدارجة | /join"))
                .addEventListeners(this)
                .build();
    }

    public void shutdown() {
        for (Long id : List.copyOf(sessions.keySet())) {
            Guild g = jda == null ? null : jda.getGuildById(id);
            if (g != null) leave(g);
        }
        sessions.values().forEach(VoiceSession::shutdown);
        sessions.clear();
        if (jda != null) {
            jda.shutdownNow();
            try {
                jda.awaitShutdown(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        JDA jda = event.getJDA();
        List<CommandData> commands = List.of(
                Commands.slash("join", "Join your voice channel / يدخل للفويس ديالك"),
                Commands.slash("leave", "Leave the voice channel / يخرج من الفويس"),
                Commands.slash("say", "Say a text out loud / يقرا شي كلام بالصوت")
                        .addOption(OptionType.STRING, "text", "What to say", true),
                Commands.slash("ask", "Ask the bot by text, it answers in voice / سول البوت")
                        .addOption(OptionType.STRING, "question", "Your question", true),
                Commands.slash("reset", "Make the bot forget the conversation / ينسى الهضرة")
        );
        Guild guild = cfg.guildId().isBlank() ? null : jda.getGuildById(cfg.guildId());
        if (guild != null) {
            guild.updateCommands().addCommands(commands).queue(
                    ok -> log().info("Slash commands registered on server " + guild.getName()),
                    err -> log().warning("Could not register slash commands: " + err.getMessage()));
        } else {
            if (!cfg.guildId().isBlank()) {
                log().warning("discord.guild-id " + cfg.guildId() + " not found (is the bot invited there?). Registering globally.");
            }
            jda.updateCommands().addCommands(commands).queue(
                    ok -> log().info("Global slash commands registered (can take up to 1 hour to appear)"),
                    err -> log().warning("Could not register slash commands: " + err.getMessage()));
        }
        log().info("Discord bot online as " + jda.getSelfUser().getName() + " in " + jda.getGuilds().size() + " server(s)");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Use me inside a server 🙂").setEphemeral(true).queue();
            return;
        }
        switch (event.getName()) {
            case "join" -> {
                AudioChannel channel = memberChannel(event.getMember());
                if (channel == null) {
                    event.reply("دخل لشي فويس شانل عاد عيط عليا 🎧").setEphemeral(true).queue();
                    return;
                }
                try {
                    join(guild, channel);
                    event.reply("ها أنا جيت ل **" + channel.getName() + "** 👋 هضرو معايا!").queue();
                } catch (Exception e) {
                    event.reply("Could not join: " + e.getMessage()).setEphemeral(true).queue();
                }
            }
            case "leave" -> {
                leave(guild);
                event.reply("بسلامة 👋").queue();
            }
            case "say" -> {
                VoiceSession s = sessions.get(guild.getIdLong());
                if (s == null) {
                    event.reply("Use /join first.").setEphemeral(true).queue();
                    return;
                }
                String text = event.getOption("text", OptionMapping::getAsString);
                event.reply("🔊 " + text).setEphemeral(true).queue();
                s.say(text, err -> event.getHook().editOriginal("❌ " + err).queue());
            }
            case "ask" -> {
                String q = event.getOption("question", OptionMapping::getAsString);
                VoiceSession s = sessions.get(guild.getIdLong());
                if (s == null) {
                    event.reply("Use /join first.").setEphemeral(true).queue();
                    return;
                }
                String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getEffectiveName();
                event.deferReply().queue();
                s.ask(name, q,
                        answer -> event.getHook().editOriginal("**" + name + ":** " + q + "\n🤖 " + answer).queue(),
                        err -> event.getHook().editOriginal("❌ " + err).queue());
            }
            case "reset" -> {
                VoiceSession s = sessions.get(guild.getIdLong());
                if (s != null) s.resetMemory();
                event.reply("🧹 نسيت كلشي.").queue();
            }
            default -> event.reply("Unknown command").setEphemeral(true).queue();
        }
    }

    /** Auto-leave when the bot is alone in the channel. */
    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (!cfg.autoLeaveWhenAlone()) return;
        Guild guild = event.getGuild();
        if (!sessions.containsKey(guild.getIdLong())) return;
        AudioChannel connected = guild.getAudioManager().getConnectedChannel();
        if (connected == null) return;
        boolean humans = connected.getMembers().stream().anyMatch(m -> !m.getUser().isBot());
        if (!humans) {
            log().info("Everyone left " + connected.getName() + ", leaving too.");
            leave(guild);
        }
    }

    // ------------------------------------------------------------------ voice control

    private static AudioChannel memberChannel(Member member) {
        if (member == null) return null;
        GuildVoiceState vs = member.getVoiceState();
        return vs == null ? null : vs.getChannel();
    }

    public void join(Guild guild, AudioChannel channel) {
        VoiceSession session = sessions.computeIfAbsent(guild.getIdLong(), id -> new VoiceSession(id, cfg, this));
        AudioManager am = guild.getAudioManager();
        am.setSendingHandler(session.speaker());
        am.setReceivingHandler(session.listener());
        am.setSelfDeafened(false);
        am.setSelfMuted(false);
        am.openAudioConnection(channel);
    }

    public void leave(Guild guild) {
        guild.getAudioManager().closeAudioConnection();
        VoiceSession s = sessions.remove(guild.getIdLong());
        if (s != null) s.shutdown();
    }

    /** First active voice session (used by Minecraft commands). */
    public VoiceSession anySession() {
        return sessions.values().stream().findFirst().orElse(null);
    }

    public void leaveAll() {
        if (jda == null) return;
        for (Long id : List.copyOf(sessions.keySet())) {
            Guild g = jda.getGuildById(id);
            if (g != null) leave(g);
        }
    }

    // ------------------------------------------------------------------ reporting

    /** Writes the conversation to the log channel and/or Minecraft chat if enabled. */
    void report(long guildId, String name, String heard, String answer) {
        if (cfg.broadcastToChat()) {
            host.broadcastToMinecraft("§9[Discord] §f" + name + ": §7" + heard + "\n§a[" + cfg.botName() + "] §f" + answer);
        }
        if (jda == null || cfg.logChannelId().isBlank()) return;
        TextChannel ch = jda.getTextChannelById(cfg.logChannelId());
        if (ch == null) return;
        String msg = "🎤 **" + safe(name) + ":** " + safe(heard) + "\n🤖 " + safe(answer);
        if (msg.length() > 1900) msg = msg.substring(0, 1900) + "…";
        ch.sendMessage(msg).queue(null, err -> log().warning("Could not write to log channel: " + err.getMessage()));
    }

    private static String safe(String s) {
        return s.replace("@", "@​");
    }
}
