package jp.example.bancontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
// 同一パッケージ内のため import は不要
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.inject.Inject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id = "bancontrol", name = "BanControl", version = "1.0")
public class BanControlPlugin {

    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("myserver", "bancontrol");
    private static final long NIGHT_START = 13000L;  // 12000L から 13000L に変更
    private static final long NIGHT_END = 23000L;   // 24000L から 23000L に変更
    private static final long DAY_TIME = 24000L;
    private static final int HEARTBEAT_INTERVAL = 30;
    private static final int MYSQL_HEARTBEAT_INTERVAL = 5;
    
    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;
    private File banFile;
    private final Map<UUID, BanInfo> banMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<UUID> deathFlagSet = Collections.synchronizedSet(new HashSet<>()); // Thread-safe化
    private final Set<UUID> adminTransferFlagSet = Collections.synchronizedSet(new HashSet<>()); // 管理者転送フラグ
    private ConfigManager configManager;
    private final Map<UUID, String> gameModeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> worldTimes = new ConcurrentHashMap<>();
    // 未使用の保留クエリは削除
    private ScheduledFuture<?> heartbeatTask; // 追加
    private HikariDataSource dataSource;
    private boolean mysqlEnabled = false;
    private boolean debugMode = false;

    @Inject
    public BanControlPlugin(ProxyServer server, @DataDirectory Path dataDirectory, Logger logger) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            // ConfigManagerを初期化
            this.configManager = new ConfigManager(dataDirectory, logger);
            this.debugMode = configManager.getBoolean("debug", false);
            logger.info("デバッグモード: {}", debugMode ? "有効" : "無効");

            // MySQL接続を初期化
            initializeMySQL();

            // データフォルダとファイルの設定
            initializeDataFiles();

            loadBans();
            startCleanupTask();

            // コマンド、イベント、チャンネルを登録
            registerCommands();
            server.getChannelRegistrar().register(CHANNEL);

            // 定期的にJigokuサーバーの時刻を確認するタスクを開始
            startTimeCheckTask();
            
            // シャットダウンフックを登録
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        } catch (Exception e) {
            logger.error("プラグイン初期化中にエラーが発生しました。", e);
        }
    }

    private void startTimeCheckTask() {
        if (mysqlEnabled) {
            startMySQLHeartbeatTask();
        } else {
            startHeartbeatTask();
        }
    }

    private void initializeDataFiles() {
        try {
            Files.createDirectories(dataDirectory);
            this.banFile = dataDirectory.resolve("bans.json").toFile();
            if (banFile.createNewFile()) {
                logger.info("Created a new bans.json file.");
            }
        } catch (IOException e) {
            logger.error("Failed to create the ban file.", e);
        }
    }

    private void registerCommands() {
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("unban").build(), 
            new UnbanCommand()
        );
        // /gense は各Bukkitサーバー側のみで処理させるため Velocity では登録しない
        logger.info("/gense コマンドは Velocity 側では登録しません (Bukkitサーバー側実装のみ使用)");
        // 管理者用コマンドの登録を削除
        // 各サーバー側で直接処理するため
    }

    private void initializeMySQL() {
        // MySQLドライバーを明示的に読み込む
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("MySQLドライバーが見つかりません。", e);
            return;
        }

        com.moandjiezana.toml.Toml mysqlConfig = configManager.getTable("mysql");
        if (mysqlConfig != null && mysqlConfig.getBoolean("enabled", false)) {
            connectToMySQL(mysqlConfig);
        }
    }

    private void connectToMySQL(com.moandjiezana.toml.Toml config) {
        String host = config.getString("host", "localhost");
        int port = config.getLong("port", 3306L).intValue();
        String database = config.getString("database", "jigoku_bancontrol");
        String username = config.getString("username", "root");
        String password = config.getString("password", "password");

        try {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                    host, port, database));
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);

            // HikariCP設定（config.tomlから読み込み）
            hikariConfig.setMaximumPoolSize(config.getLong("maximum_pool_size", 10L).intValue());
            hikariConfig.setMinimumIdle(config.getLong("minimum_idle", 2L).intValue());
            hikariConfig.setConnectionTimeout(config.getLong("connection_timeout", 30000L));
            hikariConfig.setIdleTimeout(config.getLong("idle_timeout", 600000L));
            hikariConfig.setMaxLifetime(config.getLong("max_lifetime", 1800000L));

            dataSource = new HikariDataSource(hikariConfig);
            mysqlEnabled = true;

            // テーブルを作成
            createWorldTimeTable();
            logger.info("MySQL接続プール(HikariCP)を初期化しました。");
        } catch (Exception e) {
            logger.error("MySQL接続プールの初期化に失敗しました。", e);
            mysqlEnabled = false;
        }
    }

    private void createWorldTimeTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS world_times (" +
                "world_name VARCHAR(64) PRIMARY KEY," +
                "time BIGINT NOT NULL," +
                "is_night BOOLEAN NOT NULL," +
                "last_update TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ");" // セミコロンを追加
            );
        } catch (SQLException e) {
            logger.error("world_timesテーブルの作成に失敗しました。", e);
        }
    }

    private void startMySQLHeartbeatTask() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                updateWorldTimeFromMySQL();
            } catch (Exception e) {
                logger.error("MySQL更新タスクでエラーが発生しました。", e);
            }
        }, MYSQL_HEARTBEAT_INTERVAL, MYSQL_HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    private void updateWorldTimeFromMySQL() {
        if (!mysqlEnabled || dataSource == null) return;
        
        String query = "SELECT world_name, time, is_night FROM world_times WHERE world_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, "jigoku");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long time = rs.getLong("time");
                    updateWorldTime("jigoku", time);
                    if (debugMode) logger.info("[DEBUG][MySQLHeartbeat] row found time={} (mysqlEnabled={})", time, mysqlEnabled);
                } else {
                    // DBに行が無い場合は、ハートビートで最新値の取得を試みる
                    if (debugMode) logger.info("[DEBUG][MySQLHeartbeat] row missing -> request heartbeat (mysqlEnabled={})", mysqlEnabled);
                    sendHeartbeatToJigoku();
                }
            }
        } catch (SQLException e) {
            logger.error("MySQLから時刻情報の取得に失敗しました。HikariCPが自動的に再接続を試みます。", e);
            // DBエラー時もハートビートでの取得を試みる
            if (debugMode) logger.info("[DEBUG][MySQLHeartbeat] exception -> heartbeat fallback");
            sendHeartbeatToJigoku();
        }
    }

    private void startHeartbeatTask() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeatToJigoku();
            } catch (Exception e) {
                logger.error("ハートビートタスクでエラーが発生しました。", e);
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    private void sendHeartbeatToJigoku() {
        server.getServer(configManager.getString("jigoku_server_name", "jigoku")).ifPresent(jigokuServer -> {
            Optional<Player> jigokuPlayer = findPlayerInServer(jigokuServer.getServerInfo().getName());
            
            if (jigokuPlayer.isPresent()) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("heartbeat");
                jigokuServer.sendPluginMessage(CHANNEL, out.toByteArray());
            } else {
                estimateWorldTime("jigoku");
            }
        });
    }

    private Optional<Player> findPlayerInServer(String serverName) {
        return server.getAllPlayers().stream()
            .filter(p -> p.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equals(serverName))
                .orElse(false))
            .findFirst();
    }

    private void estimateWorldTime(String worldName) {
        Long lastTime = worldTimes.get(worldName);
        if (lastTime != null) {
            long elapsedRealSeconds = HEARTBEAT_INTERVAL;
            long elapsedGameTicks = elapsedRealSeconds * 20;
            long estimatedTime = (lastTime + elapsedGameTicks) % DAY_TIME;
            updateWorldTime(worldName, estimatedTime);
        }
    }

    private void updateWorldTime(String worldName, long newTime) {
        Long lastTime = worldTimes.get(worldName);
        worldTimes.put(worldName, newTime);
        
        if (lastTime != null && "jigoku".equals(worldName)) {
            checkDayNightTransition(lastTime, newTime);
        }
    }

    private void checkDayNightTransition(long oldTime, long newTime) {
        boolean wasNight = isNightTime(oldTime);
        boolean isNight = isNightTime(newTime);
        
        if (isNight && !wasNight) {
            broadcastToGense(configManager.getString("jigoku_night_message", 
                "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
        } else if (!isNight && wasNight) {
            broadcastToGense(configManager.getString("jigoku_day_message", 
                "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
        }
    }

    private boolean isNightTime(long time) {
        return time >= NIGHT_START && time < NIGHT_END;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel = in.readUTF();
        
        try {
            handlePluginMessage(subChannel, in, event);
        } catch (Exception e) {
            logger.error("プラグインメッセージの処理中にエラーが発生しました: " + subChannel, e);
        }
    }

    private void handlePluginMessage(String subChannel, ByteArrayDataInput in, PluginMessageEvent event) {
        switch (subChannel) {
            case "jigoku_transfer":
                handleJigokuTransfer(in);
                break;
            case "gense_transfer":
                handleGenseTransfer(in);
                break;
            case "admin_jigoku_transfer":
                handleAdminJigokuTransfer(in);
                break;
            case "admin_gense_transfer":
                handleAdminGenseTransfer(in);
                break;
            case "gamemode_update":
                handleGameModeUpdate(in);
                break;
            case "death_notification":
                handleDeathNotification(in, event);
                break;
            case "night_logout":
                handleNightLogout(in);
                break;
            case "jigoku_night":
                updateWorldTime("jigoku", 13000L);
                break;
            case "jigoku_day":
                updateWorldTime("jigoku", 1000L);
                break;
            case "time_state":
                handleTimeState(in);
                break;
            case "heartbeat_response":
                handleHeartbeatResponse(in);
                break;
            case "query_jigoku_time":
                handleJigokuTimeQuery(in, event);
                break;
        }
    }

    private void handleJigokuTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 先にBAN確認（夜間ログアウトなどで地獄行きを制限したい想定）
            if (checkAndNotifyBan(player, uuid)) {
                logger.debug("[Transfer] jigoku_transfer blocked by ban uuid={}", uuid);
                return;
            }
            // 時刻チェック
            if (isJigokuNight()) {
                player.sendMessage(Component.text("§c夜の地獄は危険すぎるため、移動できません。"));
                if (debugMode) logger.info("[DEBUG][Transfer] jigoku_transfer blocked by night uuid={}", uuid);
                return;
            }

            if (debugMode) logger.info("[DEBUG][Transfer] jigoku_transfer allowed uuid={}", uuid);
            transferToServer(player, getJigokuServerName());
        });
    }

    private void handleGenseTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // GenseへはBAN解除後に戻れる想定ならBANチェックを外す（必要なら再度有効化）
            if (debugMode) logger.info("[DEBUG][Transfer] gense_transfer processing uuid={} (no ban gate)", uuid);
            transferToServer(player, getGenseServerName());
        });
    }

    // 共通のサーバー転送メソッド
    private void transferToServer(Player player, String serverName) {
        server.getServer(serverName).ifPresentOrElse(
            target -> player.createConnectionRequest(target).fireAndForget(),
            () -> {
                player.sendMessage(Component.text("§c転送先のサーバーが見つかりません: " + serverName));
                logger.error("サーバーが見つかりません: " + serverName);
            }
        );
    }

    // BANチェックの共通化
    private boolean checkAndNotifyBan(Player player, UUID uuid) {
        BanInfo banInfo = banMap.get(uuid);
        if (banInfo != null && banInfo.reason == BanInfo.Reason.NIGHT_LOGOUT) {
            long remainingSeconds = Math.max(0, (banInfo.unbanTime - System.currentTimeMillis()) / 1000);
            if (remainingSeconds > 0) {
                String message = formatBanMessage(banInfo.reason, remainingSeconds);
                player.sendMessage(Component.text(message));
                return true;
            } else {
                // 期限切れのBANを削除
                banMap.remove(uuid);
                saveBans();
            }
        }
        return false;
    }

    // BANメッセージのフォーマット
    private String formatBanMessage(BanInfo.Reason reason, long remainingSeconds) {
        long minutes = remainingSeconds / 60;
        long seconds = remainingSeconds % 60;
        
        String timeString = minutes > 0 
            ? String.format("%d分%d秒", minutes, seconds)
            : String.format("%d秒", seconds);
            
        switch (reason) {
            case NIGHT_LOGOUT:
                return String.format("§c夜間ログアウトペナルティ中です。残り時間: %s", timeString);
            case DEATH:
                return String.format("§c死亡ペナルティ中です。残り時間: %s", timeString);
            default:
                return String.format("§cペナルティ中です。残り時間: %s", timeString);
        }
    }

    private void handleGameModeUpdate(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        String gameMode = in.readUTF();
        gameModeCache.put(uuid, gameMode);
    }

    private void handleDeathNotification(ByteArrayDataInput in, PluginMessageEvent event) {
        UUID uuid = UUID.fromString(in.readUTF());
    String deathMessage = in.readUTF();
        boolean isDeathTransfer = in.readBoolean();
        // 参考ログ（内容確認用）
        if (debugMode) logger.info("[DEBUG] death_notification: message='{}' isDeathTransfer={}", deathMessage, isDeathTransfer);
        // 死亡フラグのみセット（BANは適用しない）
        deathFlagSet.add(uuid);
        
        // 死亡による転送の場合、一時的にフラグを立てる
        if (isDeathTransfer) {
            // 10秒後に自動削除
            scheduler.schedule(() -> deathFlagSet.remove(uuid), 10, TimeUnit.SECONDS);
        }

        // Genseに死亡情報を転送
        forwardMessageToServer(getGenseServerName(), event.getIdentifier(), event.getData());

        // 即座にGenseサーバーへ転送（ペナルティなし）
        server.getPlayer(uuid).ifPresent(player -> 
            transferToServer(player, getGenseServerName())
        );
    }

    // メッセージ転送の共通化
    private void forwardMessageToServer(String serverName, ChannelIdentifier identifier, byte[] data) {
        server.getServer(serverName).ifPresentOrElse(
            server -> server.sendPluginMessage(identifier, data),
            () -> logger.warn("サーバー '{}' へのメッセージ転送に失敗しました", serverName)
        );
    }

    private void handleNightLogout(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        boolean isDeathRelated = in.readBoolean();
        logger.info(String.format("[NightLogout] 受信 uuid=%s deathRelated=%s", uuid, isDeathRelated));
        
        if (shouldSkipNightLogoutPenalty(uuid, isDeathRelated)) {
            logger.info(String.format("[NightLogout] ペナルティをスキップ uuid=%s", uuid));
            return;
        }
        
        applyNightLogoutPenalty(uuid);
    }

    private boolean shouldSkipNightLogoutPenalty(UUID uuid, boolean isDeathRelated) {
        if (isDeathRelated) {
            logger.info("死亡による転送のため夜間ログアウトペナルティをスキップ: " + uuid);
            return true;
        }
        
        if (deathFlagSet.contains(uuid)) {
            logger.info("死亡フラグが立っているため夜間ログアウトペナルティをスキップ: " + uuid);
            deathFlagSet.remove(uuid);
            return true;
        }
        
        if (adminTransferFlagSet.remove(uuid)) {
            logger.info("管理者転送のため夜間ログアウトペナルティをスキップ: " + uuid);
            return true;
        }
        
        return false;
    }

    private void applyNightLogoutPenalty(UUID uuid) {
        long nightLogoutBanDuration = configManager.getInt("ban_after_night_logout_minutes", 10) * 60_000L;
        logger.info(String.format("[NightLogout] ペナルティ適用処理開始 uuid=%s durationMillis=%d", uuid, nightLogoutBanDuration));
        
        BanInfo existingBan = banMap.get(uuid);
        if (existingBan != null && shouldKeepExistingBan(existingBan, nightLogoutBanDuration)) {
            logger.info("既存のBANの方が長いため、夜間ログアウトペナルティを適用しません: " + uuid);
            return;
        }
        
        String playerName = getPlayerName(uuid, existingBan);
        
        banMap.put(uuid, new BanInfo(
            System.currentTimeMillis() + nightLogoutBanDuration, 
            BanInfo.Reason.NIGHT_LOGOUT,
            playerName
        ));
        saveBans();
        
        logger.info("夜間ログアウトペナルティを適用: " + playerName + " (" + uuid + ")");
        
        String message = String.format("§e%s が夜の地獄から逃げ出したため、%d分間のペナルティが課されました。", 
            playerName, nightLogoutBanDuration / 60_000L);
        broadcastToAllServers(message);
    }

    private boolean shouldKeepExistingBan(BanInfo existingBan, long newBanDuration) {
        long existingRemaining = existingBan.unbanTime - System.currentTimeMillis();
        return existingRemaining > newBanDuration;
    }

    private String getPlayerName(UUID uuid, BanInfo existingBan) {
        return server.getPlayer(uuid)
            .map(Player::getUsername)
            .orElseGet(() -> {
                if (existingBan != null && existingBan.username != null) {
                    return existingBan.username;
                }
                logger.info("プレイヤーがオフラインのため、UUIDで識別: " + uuid);
                return "Unknown";
            });
    }

    private void handleTimeState(ByteArrayDataInput in) {
        String state = in.readUTF();
        if ("jigoku_night".equals(state)) {
            updateWorldTime("jigoku", 13000L);
        } else if ("jigoku_day".equals(state)) {
            updateWorldTime("jigoku", 1000L);
        }
    }

    private void handleHeartbeatResponse(ByteArrayDataInput in) {
    String state = in.readUTF();
        long time = in.readLong();
        if (debugMode) logger.info("[DEBUG] heartbeat_response: state='{}' time={}", state, time);
        updateWorldTime("jigoku", time);
    }

    private boolean isJigokuNight() {
        boolean result;
        if (mysqlEnabled) {
            result = checkJigokuNightFromMySQL();
            if (debugMode) logger.info("[DEBUG][NightCheck] via MySQL -> {}", result);
        } else {
            Long time = worldTimes.get("jigoku");
            result = time != null && isNightTime(time);
            if (debugMode) logger.info("[DEBUG][NightCheck] via cache time={} -> {}", time, result);
        }
        return result;
    }

    private boolean checkJigokuNightFromMySQL() {
        if (!mysqlEnabled || dataSource == null) return false;
        
        String query = "SELECT is_night FROM world_times WHERE world_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, "jigoku");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("is_night");
                }
            }
        } catch (SQLException e) {
            logger.error("MySQLからJigokuの夜間状態の確認に失敗しました", e);
        }
        
        // フォールバック
        Long time = worldTimes.get("jigoku");
        return time != null && isNightTime(time);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String serverName = player.getCurrentServer()
            .map(s -> s.getServerInfo().getName())
            .orElse("");

        // Genseサーバーに接続し、かつdeathフラグが立っている場合
        if (getGenseServerName().equals(serverName) && deathFlagSet.remove(uuid)) {
            // Genseに擬似死亡を依頼
            sendDeathRespawnRequest(uuid);
        }
    }

    private void sendDeathRespawnRequest(UUID uuid) {
        server.getServer(getGenseServerName()).ifPresent(genseServer -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("death_respawn");
            out.writeUTF(uuid.toString());
            genseServer.sendPluginMessage(CHANNEL, out.toByteArray());
        });
    }

    private void startCleanupTask() {
        scheduler.scheduleAtFixedRate(() -> {
            boolean changed = false;
            Iterator<Map.Entry<UUID, BanInfo>> it = banMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, BanInfo> e = it.next();
                if (e.getValue().unbanTime < System.currentTimeMillis()) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) saveBans();
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void broadcastToGense(String message) {
        broadcastToServer(getGenseServerName(), message);
    }

    private void broadcastToServer(String serverName, String message) {
        server.getAllPlayers().stream()
            .filter(p -> p.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equals(serverName))
                .orElse(false))
            .forEach(p -> p.sendMessage(Component.text(message)));
    }

    private void broadcastToAllServers(String message) {
        server.getAllPlayers().forEach(p -> p.sendMessage(Component.text(message)));
    }

    private void loadBans() {
        if (!banFile.exists() || banFile.length() == 0) return;
        
        try {
            banMap.clear();
            Map<String, BanInfo> tmp = mapper.readValue(banFile, new TypeReference<Map<String, BanInfo>>() {});
            tmp.forEach((k, v) -> {
                try {
                    banMap.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException e) {
                    logger.error("無効なUUID形式をスキップ: {}", k);
                }
            });
            logger.info("{}件のBANデータを読み込みました", banMap.size());
        } catch (Exception e) {
            logger.error("bans.jsonからのBAN読み込みに失敗しました", e);
        }
    }

    private synchronized void saveBans() {
        if (banMap.isEmpty()) {
            if (debugMode) logger.info("[DEBUG] BANデータが空のため、保存をスキップします");
            return;
        }
        
        try {
            Map<String, BanInfo> toSave = new HashMap<>();
            banMap.forEach((k, v) -> toSave.put(k.toString(), v));
            mapper.writerWithDefaultPrettyPrinter().writeValue(banFile, toSave);
            if (debugMode) logger.info("[DEBUG] {}件のBANデータを保存しました", toSave.size());
        } catch (Exception e) {
            logger.error("bans.jsonへのBAN保存に失敗しました", e);
        }
    }

    // Config参照は configManager.* を直接使用

    // /unbanコマンド
    class UnbanCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (invocation.arguments().length != 1) {
                invocation.source().sendMessage(Component.text("/unban <player>"));
                return;
            }
            String name = invocation.arguments()[0];
            Optional<Player> p = server.getPlayer(name);
            if (!p.isPresent()) {
                invocation.source().sendMessage(Component.text("そのプレイヤーは見つかりません。"));
                return;
            }
            UUID uuid = p.get().getUniqueId();
            if (banMap.remove(uuid) != null) {
                saveBans();
                invocation.source().sendMessage(Component.text(name + " のBANを解除しました。"));
            } else {
                invocation.source().sendMessage(Component.text(name + " はBANされていません。"));
            }
        }
    }

    // 以前存在した Velocity 側 /gense コマンドは削除（Bukkitサーバー側で統一）

    private void handleAdminJigokuTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 管理者転送フラグを設定（30秒間有効）
            adminTransferFlagSet.add(uuid);
            scheduler.schedule(() -> adminTransferFlagSet.remove(uuid), 30, TimeUnit.SECONDS);
            
            // 管理者権限チェック（Velocityではパーミッションチェックは各サーバー側で行う）
            transferToServer(player, getJigokuServerName());
            player.sendMessage(Component.text("§a[管理者] 地獄サーバーへ強制転送しました。"));
        });
    }

    private void handleAdminGenseTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 管理者転送フラグを設定（30秒間有効）
            adminTransferFlagSet.add(uuid);
            scheduler.schedule(() -> adminTransferFlagSet.remove(uuid), 30, TimeUnit.SECONDS);
            
            // 管理者権限チェック（Velocityではパーミッションチェックは各サーバー側で行う）
            transferToServer(player, getGenseServerName());
            player.sendMessage(Component.text("§a[管理者] 現世サーバーへ強制転送しました。"));
        });
    }

    // サーバー名取得の共通化
    private String getJigokuServerName() {
        return configManager.getString("jigoku_server_name", "jigoku");
    }

    private String getGenseServerName() {
        return configManager.getString("gense_server_name", "gense");
    }

    private void cleanup() {
        // スケジューラのシャットダウン
        shutdownScheduler();
        
        // MySQL接続のクローズ
        closeMySQLConnection();
    }

    private void shutdownScheduler() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(true);
        }
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeMySQLConnection() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("MySQL接続プール(HikariCP)を正常にクローズしました");
        }
    }

    private void handleJigokuTimeQuery(ByteArrayDataInput in, PluginMessageEvent event) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            if (debugMode) logger.info("[DEBUG][TimeQuery] start mysqlEnabled={} dataSourceNull={} uuid={}", mysqlEnabled, (dataSource==null), uuid);
            if (mysqlEnabled) {
                // MySQLから時刻情報を取得して返す
                queryJigokuTimeFromMySQL(player);
            } else {
                // 推定時刻を返す
                Long time = worldTimes.get("jigoku");
                if (time != null) {
                    if (debugMode) logger.info("[DEBUG][TimeQuery] cache-hit time={}", time);
                    sendJigokuTimeResponse(player, time, isNightTime(time));
                } else {
                    // まだキャッシュが無ければ、ハートビートを送って取得を試みる
                    if (debugMode) logger.info("[DEBUG][TimeQuery] cache-miss -> heartbeat");
                    sendHeartbeatToJigoku();
                    player.sendMessage(Component.text("§e地獄ワールドの時刻を取得中です。数秒後に/jigokutime をもう一度実行してください。"));
                }
            }
        });
    }

    private void queryJigokuTimeFromMySQL(Player player) {
        if (!mysqlEnabled || dataSource == null) {
            player.sendMessage(Component.text("§cMySQL接続が利用できません。"));
            if (debugMode) logger.info("[DEBUG][TimeQuery/MySQL] rejected mysqlEnabled={} dataSourceNull={}", mysqlEnabled, (dataSource==null));
            return;
        }
        
        scheduler.execute(() -> {
            String query = "SELECT time, is_night, last_update FROM world_times WHERE world_name = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, "jigoku");
                logger.debug("[TimeQuery/MySQL] executing query uuid={} thread={}", player.getUniqueId(), Thread.currentThread().getName());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long time = rs.getLong("time");
                        boolean isNight = rs.getBoolean("is_night");
                        Timestamp lastUpdate = rs.getTimestamp("last_update");
                        logger.debug("[TimeQuery/MySQL] row time={} isNight={} lastUpdate={}", time, isNight, lastUpdate);
                        
                        // 結果を送信
                        sendJigokuTimeResponseWithDetails(player, time, isNight, lastUpdate);
                    } else {
                        logger.debug("[TimeQuery/MySQL] no row -> heartbeat + cache fallback");
                        // 行が無い場合はハートビートでの取得を試み、キャッシュがあればそれで応答
                        sendHeartbeatToJigoku();
                        Long cached = worldTimes.get("jigoku");
                        if (cached != null) {
                            logger.debug("[TimeQuery/MySQL] cache-after-miss time={}", cached);
                            sendJigokuTimeResponse(player, cached, isNightTime(cached));
                        } else {
                            player.sendMessage(Component.text("§e時刻情報を取得中です。数秒後に/jigokutime をもう一度実行してください。"));
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("MySQLから時刻情報の取得に失敗しました。", e);
                // エラー時もハートビートを送ってフォールバック
                logger.debug("[TimeQuery/MySQL] exception -> heartbeat fallback");
                sendHeartbeatToJigoku();
                Long cached = worldTimes.get("jigoku");
                if (cached != null) {
                    sendJigokuTimeResponse(player, cached, isNightTime(cached));
                } else {
                    player.sendMessage(Component.text("§e現在データベースに接続できません。数秒後に/jigokutime をもう一度実行してください。"));
                }
            }
        });
    }

    private void sendJigokuTimeResponse(Player player, long time, boolean isNight) {
        long normalizedTime = time % 24000;
        
        player.sendMessage(Component.text("§6=== 地獄ワールドの時刻情報 ==="));
        player.sendMessage(Component.text(String.format("§e現在時刻: §f%d §7/ 24000", normalizedTime)));
        player.sendMessage(Component.text(String.format("§e時間帯: %s", isNight ? "§c夜" : "§a昼")));
        
        if (isNight) {
            long ticksUntilDay = 23000 - normalizedTime;  // 24000 から 23000 に変更
            long secondsUntilDay = ticksUntilDay / 20;
            player.sendMessage(Component.text(String.format("§e朝まで: §f%d秒 §7(%dティック)", secondsUntilDay, ticksUntilDay)));
            player.sendMessage(Component.text("§c※ 夜間は/genseコマンドが使用できません"));
        } else {
            long ticksUntilNight = 13000 - normalizedTime;  // 12000 から 13000 に変更
            if (ticksUntilNight < 0) ticksUntilNight += 24000;
            long secondsUntilNight = ticksUntilNight / 20;
            player.sendMessage(Component.text(String.format("§e夜まで: §f%d秒 §7(%dティック)", secondsUntilNight, ticksUntilNight)));
            player.sendMessage(Component.text("§a※ 昼間は/genseコマンドで現世に戻れます"));
        }
    }

    private void sendJigokuTimeResponseWithDetails(Player player, long time, boolean isNight, Timestamp lastUpdate) {
        long normalizedTime = time % 24000;
        
        player.sendMessage(Component.text("§6=== 地獄ワールドの時刻情報 ==="));
        player.sendMessage(Component.text(String.format("§e現在時刻: §f%d §7/ 24000", normalizedTime)));
        player.sendMessage(Component.text(String.format("§e時間帯: %s §7(DB: %s)", 
            isNight ? "§c夜" : "§a昼",
            isNight ? "夜" : "昼")));
        
        if (isNight) {
            long ticksUntilDay = 23000 - normalizedTime;  // 24000 から 23000 に変更
            long secondsUntilDay = ticksUntilDay / 20;
            player.sendMessage(Component.text(String.format("§e朝まで: §f%d秒 §7(%dティック)", secondsUntilDay, ticksUntilDay)));
            player.sendMessage(Component.text("§c※ 夜間はサーバー移動ができません！"));
        } else {
            long ticksUntilNight = 13000 - normalizedTime;  // 12000 から 13000 に変更
            if (ticksUntilNight < 0) ticksUntilNight += 24000;
            long secondsUntilNight = ticksUntilNight / 20;
            player.sendMessage(Component.text(String.format("§e夜まで: §f%d秒 §7(%dティック)", secondsUntilNight, ticksUntilNight)));
            player.sendMessage(Component.text("§a※ 昼間はサーバー移動可能です"));
        }
        
        // 最終更新時刻を表示
        if (lastUpdate != null) {
            long secondsAgo = (System.currentTimeMillis() - lastUpdate.getTime()) / 1000;
            player.sendMessage(Component.text(String.format("§7最終更新: %d秒前", Math.max(0, secondsAgo))));
        }
    }
}
