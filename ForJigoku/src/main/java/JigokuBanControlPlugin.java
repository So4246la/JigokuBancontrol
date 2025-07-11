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

import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.logging.Level;
import java.util.stream.Collectors;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.io.ByteArrayDataInput;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.*;

public class JigokuBanControlPlugin extends JavaPlugin implements Listener, PluginMessageListener, CommandExecutor {

    private static final String CHANNEL = "myserver:bancontrol";
    private static final String BUNGEECORD_CHANNEL = "BungeeCord";
    private static final long DAY_TIME = 24000L;
    private static final long NIGHT_START = 12000L;
    private static final long NIGHT_END = 24000L;
    private static final int TIME_CHECK_INTERVAL = 100; // 5秒 (100 ticks)
    private static final long PENDING_MESSAGE_EXPIRE_TIME = 86400000L; // 24時間
    
    private final Set<UUID> deadPlayers = new HashSet<>();
    private final Set<UUID> joinedPlayers = new HashSet<>();
    private final Set<UUID> recentlyDiedPlayers = new HashSet<>(); // 最近死亡したプレイヤーを追跡
    private File dataFile;
    private FileConfiguration dataConfig;
    private final Random random = new Random();
    private int spawnRangeMin = 5000; // 最小スポーン距離
    private int spawnRangeMax = 20000; // 最大スポーン距離
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
        loadConfiguration();

        // MySQL接続を初期化
        initializeMySQL();

        // チャンネル登録
        registerChannels();
        
        // イベントとコマンドの登録
        Bukkit.getPluginManager().registerEvents(this, this);
        this.getCommand("gense").setExecutor(this);
        this.getCommand("admingense").setExecutor(this);
        
        getLogger().info("JigokuBanControlが有効になりました。");
        
        // データファイルのセットアップ
        setupDataFile();
        loadData();

        // 昼夜監視タスクを開始
        startDayNightMonitor();
    }

    private void loadConfiguration() {
        FileConfiguration config = getConfig();
        spawnRangeMin = config.getInt("spawn-range-min", 5000);
        spawnRangeMax = config.getInt("spawn-range-max", 20000);
        
        // 設定値の検証
        if (spawnRangeMin < 0 || spawnRangeMax < spawnRangeMin) {
            getLogger().warning("不正なスポーン範囲設定を検出しました。デフォルト値を使用します。");
            spawnRangeMin = 5000;
            spawnRangeMax = 20000;
        }
    }

    private void registerChannels() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, BUNGEECORD_CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
    }

    private void startDayNightMonitor() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            World world = getMainWorld();
            if (world != null) {
                checkAndUpdateTimeState(world);
            }
        }, 0L, TIME_CHECK_INTERVAL);
    }

    private World getMainWorld() {
        return getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
    }

    private void checkAndUpdateTimeState(World world) {
        boolean isCurrentlyNight = isNight(world);
        
        // MySQLに時刻情報を更新
        if (mysqlEnabled) {
            updateWorldTime(world.getName(), world.getTime(), isCurrentlyNight);
        }
        
        // 昼夜の変化を検出
        if (isCurrentlyNight != wasNight) {
            String state = isCurrentlyNight ? "jigoku_night" : "jigoku_day";
            sendTimeStateToProxy(state);
            wasNight = isCurrentlyNight;
            
            // プレイヤーへの通知
            notifyPlayersOfTimeChange(isCurrentlyNight);
        }
    }

    private void notifyPlayersOfTimeChange(boolean isNight) {
        String message = isNight 
            ? "§c夜が訪れました。死の危険が増大します..."
            : "§a朝が訪れました。/gense で現世に戻ることができます。";
        
        Bukkit.getOnlinePlayers().forEach(player -> player.sendMessage(message));
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
    
    // 死亡データの記録
    recordPlayerDeath(uuid);
    
    // Velocityに死亡情報を送信
    sendDeathNotification(player, event.getDeathMessage());
}

    private void recordPlayerDeath(UUID uuid) {
        deadPlayers.add(uuid);
        recentlyDiedPlayers.add(uuid);
        saveData();
        
        // 10秒後に最近死亡したプレイヤーのリストから削除
        Bukkit.getScheduler().runTaskLater(this, () -> {
            recentlyDiedPlayers.remove(uuid);
        }, 200L);
    }

    private void sendDeathNotification(Player player, String deathMessage) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("death_notification");
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(deathMessage != null ? deathMessage : player.getName() + " died.");
        out.writeBoolean(true); // 死亡による転送フラグ
        
        sendPluginMessage(out.toByteArray());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        
        // 死亡による退出の場合はスキップ
        if (recentlyDiedPlayers.contains(playerUuid)) {
            getLogger().info("死亡による転送のため夜間ログアウトペナルティをスキップ: " + player.getName());
            recentlyDiedPlayers.remove(playerUuid);
            return;
        }
        
        // 夜間ログアウトの処理
        if (isNight(player.getWorld())) {
            handleNightLogout(player);
        }
    }

    private void handleNightLogout(Player player) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        
        // 既に保留リストにある場合はスキップ
        if (isPendingNightLogout(playerUuid)) {
            getLogger().info("既に保留リストに存在: " + playerName);
            return;
        }
        
        // 夜間ログアウトを送信または保留
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("night_logout");
        out.writeUTF(playerUuid.toString());
        out.writeBoolean(false); // 死亡による転送ではない
        
        if (!sendPluginMessage(out.toByteArray())) {
            // 送信できない場合は保留
            savePendingNightLogout(playerUuid, playerName);
        }
    }

    private boolean isPendingNightLogout(UUID uuid) {
        List<String> pendingList = dataConfig.getStringList("pending-night-logouts");
        return pendingList.contains(uuid.toString());
    }

    private void savePendingNightLogout(UUID uuid, String playerName) {
        List<String> pendingList = new ArrayList<>(dataConfig.getStringList("pending-night-logouts"));
        pendingList.add(uuid.toString());
        dataConfig.set("pending-night-logouts", pendingList);
        dataConfig.set("pending-night-logout-time." + uuid.toString(), System.currentTimeMillis());
        dataConfig.set("pending-night-logout-name." + uuid.toString(), playerName);
        saveData();
        getLogger().info("夜間ログアウト通知を保留しました: " + playerName);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 保留中のメッセージを処理
        processPendingMessages(player);
        
        // プレイヤーの状態に応じた処理
        handlePlayerJoinState(player, playerUUID);
    }

    private void processPendingMessages(Player player) {
        // 保留中の夜間ログアウト通知を送信
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sendPendingNightLogouts(player);
        }, 20L);
    }

    private void sendPendingNightLogouts(Player player) {
        List<String> pendingList = new ArrayList<>(dataConfig.getStringList("pending-night-logouts"));
        if (pendingList.isEmpty()) return;
        
        List<String> toRemove = new ArrayList<>();
        long currentTime = System.currentTimeMillis();
        
        for (String uuidString : pendingList) {
            Long timestamp = dataConfig.getLong("pending-night-logout-time." + uuidString);
            
            // 期限切れのデータを削除
            if (timestamp != null && currentTime - timestamp > PENDING_MESSAGE_EXPIRE_TIME) {
                toRemove.add(uuidString);
                cleanupPendingData(uuidString);
                continue;
            }
            
            // 夜間ログアウト通知を送信
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("night_logout");
            out.writeUTF(uuidString);
            out.writeBoolean(false);
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            
            toRemove.add(uuidString);
            cleanupPendingData(uuidString);
        }
        
        // 処理済みのデータを削除
        pendingList.removeAll(toRemove);
        updatePendingList(pendingList);
    }

    private void cleanupPendingData(String uuidString) {
        dataConfig.set("pending-night-logout-time." + uuidString, null);
        dataConfig.set("pending-night-logout-name." + uuidString, null);
    }

    private void updatePendingList(List<String> pendingList) {
        if (pendingList.isEmpty()) {
            dataConfig.set("pending-night-logouts", null);
        } else {
            dataConfig.set("pending-night-logouts", pendingList);
        }
        saveData();
    }

    private void handlePlayerJoinState(Player player, UUID playerUUID) {
        if (deadPlayers.contains(playerUUID)) {
            handleDeadPlayerJoin(player, playerUUID);
        } else if (!joinedPlayers.contains(playerUUID)) {
            handleFirstTimeJoin(player, playerUUID);
        } else {
            handleRegularJoin(player);
        }
    }

    private void handleDeadPlayerJoin(Player player, UUID playerUUID) {
        teleportToRandomLocation(player, "§cあなたは死から蘇り、見知らぬ場所へ飛ばされた...");
        deadPlayers.remove(playerUUID);
        joinedPlayers.add(playerUUID);
        saveData();
    }

    private void handleFirstTimeJoin(Player player, UUID playerUUID) {
        teleportToRandomLocation(player, "§e地獄へようこそ！");
        joinedPlayers.add(playerUUID);
        saveData();
        
        // 初参加者向けの情報表示
        showWelcomeMessage(player);
    }

    private void handleRegularJoin(Player player) {
        player.sendMessage("§e再び地獄へようこそ！");
        showTimeReminder(player);
    }

    private void showWelcomeMessage(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            player.sendMessage("§6========================================");
            player.sendMessage("§e地獄ワールドでは昼間のみ現世に戻ることができます。");
            player.sendMessage("§a/gense §7- 昼間に現世へ戻る（夜間は使用不可）");
            player.sendMessage("§c夜間は危険です！死亡すると遠くへ飛ばされます。");
            player.sendMessage("§6========================================");
        }, 40L);
    }

    private void showTimeReminder(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (isNight(player.getWorld())) {
                player.sendMessage("§c現在は夜間です。朝まで生き延びてください！");
            } else {
                player.sendMessage("§a昼間です。/gense で現世に戻ることができます。");
            }
        }, 40L);
    }

    private void teleportToRandomLocation(Player player, String welcomeMessage) {
        World world = player.getWorld();
        
        // 安全なテレポート先を取得
        Location randomLocation = getSafeSpawnLocation(world);
        
        // プレイヤーをランダムな座標にテレポート
        Bukkit.getScheduler().runTask(this, () -> {
            player.teleport(randomLocation);
            player.sendMessage(welcomeMessage);
            
            // スポーン地点の座標を表示
            player.sendMessage(String.format("§7スポーン地点: X:%d Y:%d Z:%d", 
                randomLocation.getBlockX(), 
                randomLocation.getBlockY(), 
                randomLocation.getBlockZ()));
        });
    }

    private Location getSafeSpawnLocation(World world) {
        if (world == null) {
            world = getMainWorld();
        }
        
        // キャッシュを使用した高速化
        List<Location> cachedLocations = findMultipleSafeLocations(world, 5);
        if (!cachedLocations.isEmpty()) {
            return cachedLocations.get(random.nextInt(cachedLocations.size()));
        }
        
        // キャッシュが見つからない場合は強制的に場所を確保
        return forceFindSafeLocation(world);
    }

    private List<Location> findMultipleSafeLocations(World world, int count) {
        List<Location> locations = new ArrayList<>();
        int maxAttempts = 50; // 試行回数を制限
        
        for (int i = 0; i < maxAttempts && locations.size() < count; i++) {
            int distance = spawnRangeMin + random.nextInt(spawnRangeMax - spawnRangeMin);
            double angle = random.nextDouble() * 2 * Math.PI;
            int x = (int)(distance * Math.cos(angle));
            int z = (int)(distance * Math.sin(angle));
            
            Location loc = world.getEnvironment() == World.Environment.NETHER
                ? findSafeNetherLocation(world, x, z)
                : findSafeNormalLocation(world, x, z);
                
            if (loc != null) {
                locations.add(loc);
            }
        }
        
        return locations;
    }

    private Location findSafeNormalLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        // 海面より低い場合は、海面まで引き上げる
        if (y < world.getSeaLevel()) {
            y = world.getSeaLevel();
        }

        for (int i = 0; i < 10; i++) { // 10回試行
            Material ground = world.getBlockAt(x, y, z).getType();
            Material feet = world.getBlockAt(x, y + 1, z).getType();
            Material head = world.getBlockAt(x, y + 2, z).getType();

            if (isSolidGround(ground) && !isDangerousBlock(ground) && isSafeToStand(feet) && isSafeToStand(head) && isSurroundingSafe(world, x, y + 1, z)) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
            y++; // 少し上にずらして再試行
        }
        return null;
    }

    private Location findSafeNetherLocation(World world, int x, int z) {
        // 32から100の間で安全な場所を探す
        for (int y = 32; y < 100; y++) {
            Material feetMaterial = world.getBlockAt(x, y, z).getType();
            Material headMaterial = world.getBlockAt(x, y + 1, z).getType();
            Material groundMaterial = world.getBlockAt(x, y - 1, z).getType();

            // 2ブロックの空間があり、足元が固体ブロックであること
            if (isSafeToStand(feetMaterial) && isSafeToStand(headMaterial) && isSolidGround(groundMaterial) && !isDangerousBlock(groundMaterial)) {
                 if (isSurroundingSafe(world, x, y, z)) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    private Location forceFindSafeLocation(World world) {
        // メソッド名を修正: forceFinSafeLocation -> forceFindSafeLocation
        // 最小範囲の近くで必ず見つける
        for (int attempts = 0; attempts < 1000; attempts++) {
            int distance = spawnRangeMin + random.nextInt(2000); // 最小距離+2000の範囲
            double angle = random.nextDouble() * 2 * Math.PI;
            int x = (int)(distance * Math.cos(angle));
            int z = (int)(distance * Math.sin(angle));
            
            // Y座標を強制的に安全な高さに設定
            int y;
            if (world.getEnvironment() == World.Environment.NETHER) {
                y = 70; // ネザーの中間あたり
            } else {
                y = world.getHighestBlockYAt(x, z) + 1;
                if (y < 64) y = 64; // 海面レベル以上
            }
            
            // 最低限の安全チェックのみ
            Material below = world.getBlockAt(x, y - 1, z).getType();
            Material feet = world.getBlockAt(x, y, z).getType();
            Material head = world.getBlockAt(x, y + 1, z).getType();
            
            if (feet.isAir() && head.isAir() && !below.isAir() && !isDangerousBlock(below)) {
                getLogger().warning(String.format("強制的にスポーン地点を設定: x=%d, y=%d, z=%d", x, y, z));
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        
        // 最終手段：原点から離れた固定位置
        int emergencyX = spawnRangeMin;
        int emergencyZ = spawnRangeMin;
        int emergencyY = world.getEnvironment() == World.Environment.NETHER ? 70 : 100;
        
        getLogger().severe("安全なスポーン地点が見つからなかったため、緊急スポーン地点を使用します。");
        return new Location(world, emergencyX, emergencyY, emergencyZ);
    }
    
    // プレイヤーが立てる空間かチェック（改善版）
    private boolean isSafeToStand(Material material) {
        return material.isAir() || 
               material == Material.CAVE_AIR || 
               material == Material.VOID_AIR ||
               material == Material.TALL_GRASS ||
               material == Material.FERN ||
               material == Material.LARGE_FERN ||
               material == Material.DEAD_BUSH ||
               material == Material.VINE ||
               material == Material.SUGAR_CANE ||
               material == Material.WHEAT ||
               material == Material.CARROTS ||
               material == Material.POTATOES ||
               material == Material.BEETROOTS ||
               (!material.isSolid() && !material.name().contains("WATER") && !material.name().contains("LAVA"));
    }
    
    // 固体の地面かチェック（改善版）
    private boolean isSolidGround(Material material) {
        return material.isSolid() && 
               material != Material.BARRIER &&
               material != Material.BEDROCK && // ベッドロックの上は避ける（奈落の可能性）
               !material.name().contains("SIGN") &&
               !material.name().contains("BANNER") &&
               !material.name().contains("DOOR") &&
               !material.name().contains("GATE") &&
               !material.name().contains("TRAPDOOR") &&
               !material.name().contains("SLAB") && // ハーフブロックは避ける
               !material.name().contains("STAIRS"); // 階段も避ける
    }
    
    // 危険なブロックかチェック（改善版）
    private boolean isDangerousBlock(Material material) {
        return material == Material.LAVA || 
               material == Material.WATER ||
               material == Material.FIRE ||
               material == Material.SOUL_FIRE ||
               material == Material.CAMPFIRE ||
               material == Material.SOUL_CAMPFIRE ||
               material == Material.MAGMA_BLOCK ||
               material == Material.SWEET_BERRY_BUSH ||
               material == Material.WITHER_ROSE ||
               material == Material.CACTUS ||
               material == Material.POWDER_SNOW ||
               material == Material.POINTED_DRIPSTONE ||
               material.name().contains("PRESSURE_PLATE") ||
               material.name().contains("TRIPWIRE") ||
               material.name().contains("TNT") ||
               material.name().contains("PISTON") ||
               material.name().contains("OBSERVER");
    }
    
    // 周囲の安全性をチェック（改善版）
    private boolean isSurroundingSafe(World world, int x, int y, int z) {
        // 5x5x3の範囲で危険なブロックがないかチェック
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Material block = world.getBlockAt(x + dx, y + dy, z + dz).getType();
                    // 即座に危険なブロック
                    if (block == Material.LAVA || block == Material.FIRE || block == Material.SOUL_FIRE) {
                        return false;
                    }
                    // 近くにあると危険なブロック
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                        if (block == Material.CACTUS || block == Material.SWEET_BERRY_BUSH || 
                            block == Material.WITHER_ROSE || block == Material.MAGMA_BLOCK ||
                            block == Material.CAMPFIRE || block == Material.SOUL_CAMPFIRE) {
                            return false;
                        }
                    }
                }
            }
        }
        
        // 上方向の安全性（落下物チェック）
        for (int dy = 2; dy <= 5; dy++) {
            Material above = world.getBlockAt(x, y + dy, z).getType();
            if (above == Material.SAND || above == Material.GRAVEL || 
                above == Material.ANVIL || above == Material.POINTED_DRIPSTONE ||
                above.name().contains("CONCRETE_POWDER")) {
                return false;
            }
        }
        
        return true;
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
            World world = player.getWorld();
            long time = world.getTime();
            boolean nightCheck = isNight(world);
            
            // デバッグ情報を詳細に出力
            getLogger().info(String.format("=== Genseコマンド実行 ==="));
            getLogger().info(String.format("プレイヤー: %s", player.getName()));
            getLogger().info(String.format("ワールド: %s", world.getName()));
            getLogger().info(String.format("現在時刻: %d", time));
            getLogger().info(String.format("夜判定: %b (12000-24000の範囲: %b)", nightCheck, (time >= 12000 && time < 24000)));
            getLogger().info(String.format("wasNight状態: %b", wasNight));
            
            // 時刻を0-24000の範囲に正規化（念のため）
            long normalizedTime = time % 24000;
            boolean isNightTime = normalizedTime >= 12000 && normalizedTime < 24000;
            
            if (isNightTime) {
                player.sendMessage("§c夜の地獄からは脱出できません。朝まで待ってください。");
                player.sendMessage("§7(現在の時刻: " + normalizedTime + "/24000)");
                player.sendMessage("§7(朝になるまで: " + (24000 - normalizedTime) + "ティック)");
                
                // 管理者向けデバッグ情報（必要に応じて）
                if (player.hasPermission("jigokuban.debug")) {
                    player.sendMessage("§7[DEBUG] World: " + world.getName() + ", Raw time: " + time);
                }
                
                return true;
            }

            // 昼間の場合のみ転送処理を実行
            getLogger().info("昼間と判定されたため、転送処理を開始します。");
            
            // Velocityに転送リクエストを送信
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("gense_transfer");
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            
            player.sendMessage("§a現世への移動を開始します...");
            player.sendMessage("§7(現在の時刻: " + normalizedTime + "/24000 - 昼間)");
            return true;
        } else if (command.getName().equalsIgnoreCase("admingense")) {
            // OP権限チェック
            if (!player.isOp()) {
                player.sendMessage("§cこのコマンドを実行する権限がありません。");
                return true;
            }
            
            // 管理者用の現世転送
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("admin_gense_transfer");
            out.writeUTF(player.getUniqueId().toString());
            sendPluginMessage(out.toByteArray());
            
            player.sendMessage("§a[管理者] 現世への強制転送を開始します...");
            getLogger().info(String.format("[管理者転送] %s が現世への強制転送を実行しました。", player.getName()));
            return true;
        }

        return false;
    }

    private void connectToServer(Player player, String serverName) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(serverName);
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();
        
        switch (subChannel) {
            case "heartbeat":
                // ハートビート応答を送信
                sendHeartbeatResponse();
                break;
        }
    }

    private void sendHeartbeatResponse() {
        World world = getServer().getWorlds().get(0);
        if (world != null) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("heartbeat_response");
            out.writeUTF(isNight(world) ? "jigoku_night" : "jigoku_day");
            out.writeLong(world.getTime());
            
            sendPluginMessage(out.toByteArray());
        }
    }

    private void sendTimeStateToProxy(String state) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("time_state");
        out.writeUTF(state);
        
        sendPluginMessage(out.toByteArray());
    }

    private boolean sendPluginMessage(byte[] message) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        
        if (!players.isEmpty()) {
            players.iterator().next().sendPluginMessage(this, CHANNEL, message);
            return true;
        }
        return false;
    }
}
