package jp.example.gense;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class GenseDeathRespawnListener extends JavaPlugin implements PluginMessageListener, Listener {

    private static final String CHANNEL = "myserver:bancontrol";
    private Map<UUID, Integer> playerLives = new HashMap<>();
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private int startingLives;
    private long revivalTimeTicks;

    @Override
    public void onEnable() {
        // Plugin Channels
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);

        // Configuration
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        startingLives = config.getInt("starting-lives", 3);
        long revivalTimeMinutes = config.getLong("revival-time-minutes", 60);
        revivalTimeTicks = revivalTimeMinutes * 60 * 20;

        // Player Data
        setupPlayerDataFile();
        loadPlayerData();

        // Revival Timer
        getServer().getScheduler().runTaskTimer(this, this::revivalTask, 0L, revivalTimeTicks);

        getLogger().info("GenseDeathRespawnListenerが有効になりました。");
    }

    @Override
    public void onDisable() {
        savePlayerData();
        getLogger().info("GenseDeathRespawnListenerが無効になりました。");
    }

    private void setupPlayerDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "playerdata.ymlの作成に失敗しました。", e);
            }
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void loadPlayerData() {
        if (playerDataConfig.contains("lives")) {
            playerDataConfig.getConfigurationSection("lives").getKeys(false).forEach(uuidString -> {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    int lives = playerDataConfig.getInt("lives." + uuidString);
                    playerLives.put(uuid, lives);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("無効なUUIDがplayerdata.ymlにあります: " + uuidString);
                }
            });
        }
    }

    private void savePlayerData() {
        if (playerDataConfig == null || playerDataFile == null) return;
        // Clear old data
        playerDataConfig.set("lives", null);
        // Save new data
        playerLives.forEach((uuid, lives) -> {
            playerDataConfig.set("lives." + uuid.toString(), lives);
        });
        try {
            playerDataConfig.save(playerDataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "playerdata.ymlの保存に失敗しました。", e);
        }
    }

    private void revivalTask() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            int currentLives = getLives(uuid);
            if (currentLives < startingLives) {
                setLives(uuid, currentLives + 1);
                player.sendMessage("§a[通知] 残機が1回復しました！ (現在: " + getLives(uuid) + "機)");
                if (player.getGameMode() == GameMode.SPECTATOR && getLives(uuid) > 0) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.sendMessage("§a残機が回復したため、サバイバルモードに戻りました。");
                }
            }
        }
    }

    private int getLives(UUID uuid) {
        return playerLives.getOrDefault(uuid, startingLives);
    }

    private void setLives(UUID uuid, int lives) {
        playerLives.put(uuid, lives);
        savePlayerData(); // Save immediately on change
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String sub = in.readUTF();
        UUID uuid;

        switch (sub) {
            case "death_respawn":
                uuid = UUID.fromString(in.readUTF());
                Player targetPlayer = Bukkit.getPlayer(uuid);
                if (targetPlayer != null) {
                    handlePlayerDeath(targetPlayer);
                }
                break;

            case "death":
                String deathMessage = in.readUTF();
                Bukkit.broadcastMessage("§c[地獄での死亡] " + deathMessage);
                break;

            case "query_gamemode":
                uuid = UUID.fromString(in.readUTF());
                Player queriedPlayer = Bukkit.getPlayer(uuid);
                if (queriedPlayer != null) {
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("gamemode_response");
                    out.writeUTF(uuid.toString());
                    // 残機が0ならSPECTATOR、そうでなければ現在のゲームモードを返す
                    String gameMode = getLives(uuid) > 0 ? queriedPlayer.getGameMode().name() : "SPECTATOR";
                    out.writeUTF(gameMode);
                    // メッセージはVelocityから来たので、任意のプレイヤー経由で返信
                    // 本来はリクエスト元のプロキシに返すべきだが、Bukkit APIでは難しい
                    // オンラインプレイヤーがいれば誰でも良い
                    if (!Bukkit.getOnlinePlayers().isEmpty()) {
                       Bukkit.getOnlinePlayers().iterator().next().sendPluginMessage(this, CHANNEL, out.toByteArray());
                    }
                }
                break;
        }
    }

    private void handlePlayerDeath(Player player) {
        UUID uuid = player.getUniqueId();
        int lives = getLives(uuid) - 1;
        setLives(uuid, lives);

        player.sendMessage("§c地獄で死亡したため、残機が減少しました。残り: " + lives + "機");

        if (lives <= 0) {
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("§c残機がなくなったため、スペクテイターモードになりました。");
        } else {
            // 強制的に死亡させる
            forceKillPlayer(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // 新規プレイヤーかチェック
        if (!playerLives.containsKey(uuid)) {
            setLives(uuid, startingLives);
            player.sendMessage("§aGenseへようこそ！残機は" + startingLives + "機です。");
        }
        sendGameModeUpdate(player);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        // 自動復帰などでゲームモードが変わった場合も通知
        sendGameModeUpdate(event.getPlayer());
    }

    private void sendGameModeUpdate(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("gamemode_update");
        out.writeUTF(player.getUniqueId().toString());
        String gameMode = getLives(player.getUniqueId()) > 0 ? player.getGameMode().name() : "SPECTATOR";
        out.writeUTF(gameMode);
        // このメッセージはVelocityのキャッシュ更新用なので、どのプレイヤー経由でも良い
        player.sendPluginMessage(this, CHANNEL, out.toByteArray());
    }

    private void forceKillPlayer(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            player.setHealth(0.0);
        });
    }
}
