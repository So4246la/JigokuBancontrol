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
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import jp.example.bancontrol.ConfigManager; // Add this import
import jp.example.bancontrol.BanInfo; // Add this import
import net.kyori.adventure.key.Key; // Add this import
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id = "bancontrol", name = "BanControl", version = "1.0")
public class BanControlPlugin {

    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("myserver", "bancontrol");
    private static final long NIGHT_START = 12000L;
    private static final long NIGHT_END = 24000L;
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
    private ConfigManager configManager;
    private final Map<UUID, String> gameModeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> worldTimes = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<String>> pendingGameModeQueries = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Long>> pendingTimeQueries = new ConcurrentHashMap<>();
    private ScheduledFuture<?> heartbeatTask; // 追加
    private Connection mysqlConnection;
    private boolean mysqlEnabled = false;

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
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("gense").build(), 
            new GenseCommand()
        );
        // 管理者用コマンドを追加
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("admingense").build(), 
            new AdminGenseCommand()
        );
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("adminjigoku").build(), 
            new AdminJigokuCommand()
        );
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
            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&autoReconnect=true", 
                host, port, database);
            mysqlConnection = DriverManager.getConnection(url, username, password);
            mysqlEnabled = true;

            // テーブルを作成
            createWorldTimeTable();
            logger.info("MySQL接続に成功しました。");
        } catch (SQLException e) {
            logger.error("MySQL接続に失敗しました。", e);
            mysqlEnabled = false;
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
        if (!mysqlEnabled || mysqlConnection == null) return;
        
        String query = "SELECT world_name, time, is_night FROM world_times WHERE world_name = ?";
        try (PreparedStatement stmt = mysqlConnection.prepareStatement(query)) {
            stmt.setString(1, "jigoku");
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long time = rs.getLong("time");
                    updateWorldTime("jigoku", time);
                }
            }
        } catch (SQLException e) {
            logger.error("MySQLから時刻情報の取得に失敗しました。", e);
            checkMySQLConnection();
        }
    }

    private void checkMySQLConnection() {
        try {
            if (mysqlConnection == null || mysqlConnection.isClosed()) {
                logger.info("MySQL接続が切断されました。再接続を試みます。");
                initializeMySQL();
            }
        } catch (SQLException e) {
            logger.error("MySQL接続の確認に失敗しました。", e);
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
        }
    }

    private void handleJigokuTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 時刻チェック
            if (isJigokuNight()) {
                player.sendMessage(Component.text("§c夜の地獄は危険すぎるため、移動できません。"));
                return;
            }

            transferToServer(player, getJigokuServerName());
        });
    }

    private void handleGenseTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 夜間ログアウトのBANチェックのみ
            if (checkAndNotifyBan(player, uuid)) {
                return;
            }

            // Genseサーバーへ移動（ペナルティなし）
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
        
        if (shouldSkipNightLogoutPenalty(uuid, isDeathRelated)) {
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
            return true;
        }
        
        return false;
    }

    private void applyNightLogoutPenalty(UUID uuid) {
        long nightLogoutBanDuration = configManager.getInt("ban_after_night_logout_minutes", 10) * 60_000L;
        
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
        updateWorldTime("jigoku", time);
    }

    private boolean isJigokuNight() {
        if (mysqlEnabled) {
            return checkJigokuNightFromMySQL();
        }
        
        Long time = worldTimes.get("jigoku");
        return time != null && isNightTime(time);
    }

    private boolean checkJigokuNightFromMySQL() {
        if (mysqlConnection == null) return false;
        
        String query = "SELECT is_night FROM world_times WHERE world_name = ?";
        try (PreparedStatement stmt = mysqlConnection.prepareStatement(query)) {
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
            logger.debug("BANデータが空のため、保存をスキップします");
            return;
        }
        
        try {
            Map<String, BanInfo> toSave = new HashMap<>();
            banMap.forEach((k, v) -> toSave.put(k.toString(), v));
            mapper.writerWithDefaultPrettyPrinter().writeValue(banFile, toSave);
            logger.debug("{}件のBANデータを保存しました", toSave.size());
        } catch (Exception e) {
            logger.error("bans.jsonへのBAN保存に失敗しました", e);
        }
    }

    // Configファイル読み込みの本実装は各自で。ここはサンプル
    private String getConfig(String key) {
        return configManager.getString(key, "");
    }
    private int getConfigMinutes(String key) {
        switch (key) {
            case "ban_after_death_minutes": return configManager.getInt("ban_after_death_minutes", 5);
            case "ban_after_night_logout_minutes": return configManager.getInt("ban_after_night_logout_minutes", 5);
            default: return 5;
        }
    }

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

    // /genseコマンド
    class GenseCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            Optional<RegisteredServer> target = server.getServer(configManager.getString("gense_server_name", "gense"));
            if (target.isPresent()) {
                if (invocation.source() instanceof Player) {
                    ((Player)invocation.source()).createConnectionRequest(target.get()).fireAndForget();
                }
            } else {
                invocation.source().sendMessage(Component.text("現世サーバーが見つかりません。"));
            }
        }
    }

    // /admingenseコマンド
    class AdminGenseCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(Component.text("このコマンドはプレイヤーのみ実行できます。"));
                return;
            }
            
            Player player = (Player) invocation.source();
            
            // Velocityではパーミッションチェックができないため、
            // 各サーバー側でOP権限をチェックしてから転送リクエストを送信する仕組みにする
            player.sendMessage(Component.text("§e管理者権限の確認中..."));
            player.sendMessage(Component.text("§7※このコマンドはサーバー内で /admingense を実行してください。"));
        }
    }

    // /adminjigokuコマンド
    class AdminJigokuCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(Component.text("このコマンドはプレイヤーのみ実行できます。"));
                return;
            }
            
            Player player = (Player) invocation.source();
            
            // Velocityではパーミッションチェックができないため、
            // 各サーバー側でOP権限をチェックしてから転送リクエストを送信する仕組みにする
            player.sendMessage(Component.text("§e管理者権限の確認中..."));
            player.sendMessage(Component.text("§7※このコマンドはサーバー内で /adminjigoku を実行してください。"));
        }
    }

    private void handleAdminJigokuTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 管理者権限チェック（Velocityではパーミッションチェックは各サーバー側で行う）
            transferToServer(player, getJigokuServerName());
            player.sendMessage(Component.text("§a[管理者] 地獄サーバーへ強制転送しました。"));
        });
    }

    private void handleAdminGenseTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
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
        if (mysqlConnection != null) {
            try {
                if (!mysqlConnection.isClosed()) {
                    mysqlConnection.close();
                    logger.info("MySQL接続を正常にクローズしました");
                }
            } catch (SQLException e) {
                logger.error("MySQL接続のクローズに失敗しました", e);
            }
        }
    }
}
