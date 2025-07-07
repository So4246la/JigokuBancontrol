package jp.example.jigokubancontrol;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Random;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.*;

public class JigokuBanControlPlugin extends JavaPlugin implements Listener, PluginMessageListener, CommandExecutor {

    private static final String CHANNEL = "myserver:bancontrol";
    private final Set<UUID> deadPlayers = new HashSet<>();
    private final Set<UUID> joinedPlayers = new HashSet<>();
    private File dataFile;
    private FileConfiguration dataConfig;
    private final Random random = new Random();
    private int spawnRange = 1000; // スポーン範囲（中心からの距離）
    private boolean wasNight = false; // 最後にチェックした時の夜かどうかを保持
    private Connection mysqlConnection;
    private boolean mysqlEnabled = false;

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 12000 && time < 24000;
    }

    @Override
    public void onEnable() {
        // 設定ファイルをロード
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        spawnRange = config.getInt("spawn-range", 1000);

        // MySQL接続を初期化
        initializeMySQL();

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("JigokuBanControlが有効になりました。");
        setupDataFile();
        loadData();

        // コマンドを登録
        this.getCommand("gense").setExecutor(this);

        // 昼夜の切り替わりを監視するタスク
        getServer().getScheduler().runTaskTimer(this, () -> {
            World world = getServer().getWorlds().get(0); // メインワールドを想定
            if (world != null) {
                boolean isCurrentlyNight = isNight(world);
                
                // MySQLに時刻情報を更新
                if (mysqlEnabled) {
                    updateWorldTime(world.getName(), world.getTime(), isCurrentlyNight);
                }
                
                if (isCurrentlyNight && !wasNight) {
                    sendTimeStateToProxy("jigoku_night");
                    wasNight = true;
                }
                if (!isCurrentlyNight && wasNight) {
                    sendTimeStateToProxy("jigoku_day");
                    wasNight = false;
                }
            }
        }, 0L, 100L); // 5秒ごとにチェック (100 ticks)
    }

    private void initializeMySQL() {
        FileConfiguration config = getConfig();
        if (config.getBoolean("mysql.enabled", false)) {
            String host = config.getString("mysql.host", "localhost");
            int port = config.getInt("mysql.port", 3306);
            String database = config.getString("mysql.database", "jigoku_bancontrol");
            String username = config.getString("mysql.username", "root");
            String password = config.getString("mysql.password", "password");

            try {
                String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC", host, port, database);
                mysqlConnection = DriverManager.getConnection(url, username, password);
                mysqlEnabled = true;

                // テーブルを作成
                createWorldTimeTable();
                getLogger().info("MySQL接続に成功しました。");
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "MySQL接続に失敗しました。", e);
                mysqlEnabled = false;
            }
        }
    }

    private void createWorldTimeTable() {
        try (Statement stmt = mysqlConnection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS world_times (" +
                "world_name VARCHAR(64) PRIMARY KEY," +
                "time BIGINT NOT NULL," +
                "is_night BOOLEAN NOT NULL," +
                "last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ")"
            );
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "world_timesテーブルの作成に失敗しました。", e);
        }
    }

    private void updateWorldTime(String worldName, long time, boolean isNight) {
        if (!mysqlEnabled) return;
        
        try {
            String query = "INSERT INTO world_times (world_name, time, is_night) VALUES (?, ?, ?) " +
                          "ON DUPLICATE KEY UPDATE time = VALUES(time), is_night = VALUES(is_night)";
            try (PreparedStatement stmt = mysqlConnection.prepareStatement(query)) {
                stmt.setString(1, "jigoku"); // 常に"jigoku"として保存
                stmt.setLong(2, time);
                stmt.setBoolean(3, isNight);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "MySQLへの時刻情報の更新に失敗しました。", e);
        }
    }

    @Override
    public void onDisable() {
        saveData();
        if (mysqlConnection != null) {
            try {
                mysqlConnection.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "MySQL接続のクローズに失敗しました。", e);
            }
        }
    }

    private void setupDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "data.ymlの作成に失敗しました。", e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadData() {
        List<String> deadUuids = dataConfig.getStringList("dead-players");
        for (String uuidString : deadUuids) {
            try {
                deadPlayers.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                getLogger().warning("無効なUUID文字列をdata.ymlから読み込みました: " + uuidString);
            }
        }
        List<String> joinedUuids = dataConfig.getStringList("joined-players");
        for (String uuidString : joinedUuids) {
            try {
                joinedPlayers.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                getLogger().warning("無効なUUID文字列をdata.ymlから読み込みました: " + uuidString);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null || dataFile == null) {
            return;
        }
        List<String> deadUuidStrings = deadPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        dataConfig.set("dead-players", deadUuidStrings);
        List<String> joinedUuidStrings = joinedPlayers.stream().map(UUID::toString).collect(Collectors.toList());
        dataConfig.set("joined-players", joinedUuidStrings);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "data.ymlの保存に失敗しました。", e);
        }
    }

@EventHandler
public void onPlayerDeath(PlayerDeathEvent event) {
    Player player = event.getEntity();
    UUID uuid = player.getUniqueId();
    deadPlayers.add(uuid);
    saveData();

    // Velocityに死亡情報を送信
    ByteArrayDataOutput out = ByteStreams.newDataOutput();
    out.writeUTF("death");
    out.writeUTF(uuid.toString());
    // 死亡メッセージも送信
    String deathMessage = event.getDeathMessage();
    out.writeUTF(deathMessage != null ? deathMessage : player.getName() + " died.");
    player.sendPluginMessage(this, CHANNEL, out.toByteArray());

    // 死亡したらgenseサーバーに移動する処理はVelocity側で行うため、ここでは削除
    // connectToServer(player, "gense");
}

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isNight(player.getWorld())) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("night_logout");
            out.writeUTF(player.getUniqueId().toString());
            // プレイヤーオブジェクト経由で送信
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 保留中の時刻状態があれば送信
        String pendingState = dataConfig.getString("pending-time-state");
        if (pendingState != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                sendTimeStateToProxy(pendingState);
                dataConfig.set("pending-time-state", null);
                saveData();
            }, 20L); // 1秒後に送信
        }

        if (deadPlayers.contains(playerUUID)) {
            // 死亡からの復帰の場合、ランダムな場所にテレポート
            teleportToRandomLocation(player, "§cあなたは死から蘇り、見知らぬ場所へ飛ばされた...");
            deadPlayers.remove(playerUUID);
            if (!joinedPlayers.contains(playerUUID)) {
                joinedPlayers.add(playerUUID);
            }
            saveData();
        } else if (!joinedPlayers.contains(playerUUID)) {
            // 初参加の場合、ランダムな場所にテレポート
            teleportToRandomLocation(player, "§e地獄の世界へようこそ！");
            joinedPlayers.add(playerUUID);
            saveData();
        } else {
            // 通常の参加の場合
            player.sendMessage("§e再び地獄の世界へようこそ！");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();

        // 安全なリスポーン地点を取得
        Location randomLocation = getSafeSpawnLocation(world);

        // リスポーン地点をランダムな座標に設定
        event.setRespawnLocation(randomLocation);

        // テレポート後にメッセージを送信
        Bukkit.getScheduler().runTask(this, () -> {
            player.sendMessage("§c死の代償として、新たな場所に飛ばされた...");
        });
    }

    private void teleportToRandomLocation(Player player, String welcomeMessage) {
        World world = player.getWorld();
        
        // 安全なテレポート先を取得
        Location randomLocation = getSafeSpawnLocation(world);
        
        // プレイヤーをランダムな座標にテレポート
        Bukkit.getScheduler().runTask(this, () -> {
            player.teleport(randomLocation);
            player.sendMessage(welcomeMessage);
        });
    }

    private Location getSafeSpawnLocation(World world) {
        if (world == null) {
            world = Bukkit.getWorlds().get(0); 
        }
        for (int i = 0; i < 10; i++) {
            int x = random.nextInt(spawnRange * 2) - spawnRange;
            int z = random.nextInt(spawnRange * 2) - spawnRange;
            int y = world.getHighestBlockYAt(x, z);
            Material blockType = world.getBlockAt(x, y - 1, z).getType();

            if (blockType != Material.WATER && blockType != Material.LAVA && blockType != Material.POWDER_SNOW) {
                return new Location(world, x, y, z);
            }
        }
        // 10回試行してダメならデフォルト位置
        return new Location(world, 0, world.getHighestBlockYAt(0, 0) + 1, 0);
    }

    private void connectToServer(Player player, String serverName) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(serverName);
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    private void sendTimeStateToProxy(String state) {
        // プレイヤーがいなくても送信できるように修正
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(state);

        // Velocityに直接送信する（プレイヤー経由ではない方法）
        // プレイヤーがいる場合は最初のプレイヤー経由で送信
        if (!getServer().getOnlinePlayers().isEmpty()) {
            Player sender = getServer().getOnlinePlayers().iterator().next();
            sender.sendPluginMessage(this, CHANNEL, out.toByteArray());
            getLogger().info(state + " メッセージをVelocityに送信しました。");
        } else {
            // プレイヤーがいない場合は、時刻情報をキャッシュしておく
            getLogger().info(state + " - プレイヤーがいないため送信を保留しました。");
            // 次回プレイヤーが参加した時に送信するためのフラグを保存
            dataConfig.set("pending-time-state", state);
            saveData();
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL)) {
            return;
        }

        com.google.common.io.ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subchannel = in.readUTF();

        if (subchannel.equals("get_time")) {
            String uuidString = in.readUTF(); // UUIDを受け取る
            String worldName = in.readUTF();
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                long time = world.getTime();
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("jigoku_time_response");
                out.writeUTF(uuidString); // UUIDを返信する
                out.writeUTF(worldName);
                out.writeLong(time);
                // Velocityからのリクエストなので、player経由で返信
                player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            }
        } else if (subchannel.equals("heartbeat")) {
            // Velocityからの定期的なハートビート
            World world = getServer().getWorlds().get(0);
            if (world != null) {
                boolean isCurrentlyNight = isNight(world);
                String state = isCurrentlyNight ? "jigoku_night" : "jigoku_day";
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("heartbeat_response");
                out.writeUTF(state);
                out.writeLong(world.getTime());
                player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("gense")) {
            // 夜間チェック
            if (isNight(player.getWorld())) {
                player.sendMessage("§c夜の地獄からは脱出できません。朝まで待ってください。");
                return true;
            }

            // Velocityにサーバー移動を依頼
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("gense_transfer");
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            return true;
        }

        return false;
    }
}
