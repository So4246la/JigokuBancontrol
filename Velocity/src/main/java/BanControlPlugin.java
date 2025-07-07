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
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import jp.example.bancontrol.ConfigManager; // Add this import
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
    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;
    private File banFile;
    private final Map<UUID, BanInfo> banMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<UUID> deathFlagSet = new HashSet<>(); // Added deathFlagSet here
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
            String host = mysqlConfig.getString("host", "localhost");
            int port = mysqlConfig.getLong("port", 3306L).intValue();
            String database = mysqlConfig.getString("database", "jigoku_bancontrol");
            String username = mysqlConfig.getString("username", "root");
            String password = mysqlConfig.getString("password", "password");

            try {
                String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC", host, port, database);
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
                // MySQLから時刻情報を取得
                String query = "SELECT world_name, time, is_night FROM world_times WHERE world_name = 'jigoku'";
                try (PreparedStatement stmt = mysqlConnection.prepareStatement(query);
                     ResultSet rs = stmt.executeQuery()) {
                    
                    if (rs.next()) {
                        long time = rs.getLong("time");
                        boolean isNight = rs.getBoolean("is_night");
                        
                        Long lastTime = worldTimes.get("jigoku");
                        worldTimes.put("jigoku", time);
                        
                        // 昼夜の変化を検出
                        if (lastTime != null) {
                            boolean wasNight = lastTime >= 12000 && lastTime < 24000;
                            if (isNight && !wasNight) {
                                broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
                            } else if (!isNight && wasNight) {
                                broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("MySQLから時刻情報の取得に失敗しました。", e);
            }
        }, 5, 5, TimeUnit.SECONDS); // 5秒ごとに実行
    }

    private void startHeartbeatTask() {
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            // Jigokuサーバーに接続しているプレイヤーを探す
            server.getServer(configManager.getString("jigoku_server_name", "jigoku")).ifPresent(jigokuServer -> {
                Optional<Player> jigokuPlayer = server.getAllPlayers().stream()
                    .filter(p -> p.getCurrentServer()
                        .map(s -> s.getServerInfo().getName().equals(configManager.getString("jigoku_server_name", "jigoku")))
                        .orElse(false))
                    .findFirst();
                
                if (jigokuPlayer.isPresent()) {
                    // Jigokuにプレイヤーがいる場合、ハートビートを送信
                    ByteArrayDataOutput out = ByteStreams.newDataOutput();
                    out.writeUTF("heartbeat");
                    jigokuServer.sendPluginMessage(CHANNEL, out.toByteArray());
                } else {
                    // Jigokuにプレイヤーがいない場合、最後に記録された時刻を使用
                    // 時刻を推定（20分ごとに1日進む）
                    Long lastTime = worldTimes.get("jigoku");
                    if (lastTime != null) {
                        // 最後の更新から経過した時間を計算し、ゲーム内時間を推定
                        long elapsedRealSeconds = 30; // 30秒ごとの更新と仮定
                        long elapsedGameTicks = (elapsedRealSeconds * 20); // 1秒 = 20 ticks
                        long estimatedTime = (lastTime + elapsedGameTicks) % 24000;
                        worldTimes.put("jigoku", estimatedTime);
                        
                        // 昼夜の判定
                        boolean wasNight = lastTime >= 12000 && lastTime < 24000;
                        boolean isNight = estimatedTime >= 12000 && estimatedTime < 24000;
                        
                        if (isNight && !wasNight) {
                            broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
                        } else if (!isNight && wasNight) {
                            broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
                        }
                    }
                }
            });
        }, 30, 30, TimeUnit.SECONDS); // 30秒ごとに実行
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String subChannel = in.readUTF();
        
        try {
            switch (subChannel) {
                case "jigoku_transfer":
                    handleJigokuTransfer(in);
                    break;
                case "gense_transfer":
                    handleGenseTransfer(in);
                    break;
                case "gamemode_update":
                    handleGameModeUpdate(in);
                    break;
                case "death_notification": // "death"から変更
                    handleDeathNotification(in, event);
                    break;
                case "night_logout":
                    handleNightLogout(in);
                    break;
                case "jigoku_night":
                    handleTimeChange(13000L, configManager.getString("jigoku_night_message", 
                        "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
                    break;
                case "jigoku_day":
                    handleTimeChange(1000L, configManager.getString("jigoku_day_message", 
                        "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
                    break;
                case "time_state":
                    handleTimeState(in);
                    break;
                case "heartbeat_response":
                    handleHeartbeatResponse(in);
                    break;
            }
        } catch (Exception e) {
            logger.error("Error handling plugin message: " + subChannel, e);
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

            server.getServer(configManager.getString("jigoku_server_name", "jigoku"))
                .ifPresent(target -> player.createConnectionRequest(target).fireAndForget());
        });
    }

    private void handleGenseTransfer(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        server.getPlayer(uuid).ifPresent(player -> {
            // 夜間ログアウトのBANチェックのみ
            BanInfo banInfo = banMap.get(uuid);
            if (banInfo != null && banInfo.reason == BanInfo.Reason.NIGHT_LOGOUT) {
                long remainingSeconds = Math.max(0, (banInfo.unbanTime - System.currentTimeMillis()) / 1000);
                String message = formatBanMessage(banInfo.reason, remainingSeconds);
                player.sendMessage(Component.text(message));
                return;
            }

            // Genseサーバーへ移動（ペナルティなし）
            server.getServer(configManager.getString("gense_server_name", "gense"))
                .ifPresent(target -> player.createConnectionRequest(target).fireAndForget());
        });
    }

    private void handleGameModeUpdate(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        String gameMode = in.readUTF();
        gameModeCache.put(uuid, gameMode);
    }

    private void handleDeathNotification(ByteArrayDataInput in, PluginMessageEvent event) {
        UUID uuid = UUID.fromString(in.readUTF());
        String deathMessage = in.readUTF();
        boolean isDeathTransfer = in.readBoolean(); // 死亡による転送フラグを読み取る
        
        // 死亡フラグのみセット（BANは適用しない）
        deathFlagSet.add(uuid);
        
        // 死亡による転送の場合、一時的にフラグを立てる
        if (isDeathTransfer) {
            // 夜間退出ペナルティを回避するための一時的なフラグ
            scheduler.schedule(() -> {
                // このフラグは自動的に削除されるので、特別な処理は不要
            }, 10, TimeUnit.SECONDS);
        }

        // Genseに死亡情報を転送
        server.getServer("gense").ifPresent(gense ->
            gense.sendPluginMessage(event.getIdentifier(), event.getData())
        );

        // 即座にGenseサーバーへ転送（ペナルティなし）
        server.getPlayer(uuid).ifPresent(player ->
            server.getServer("gense").ifPresent(target ->
                player.createConnectionRequest(target).fireAndForget()
            )
        );
    }

    private void handleNightLogout(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        boolean isDeathRelated = in.readBoolean(); // 死亡関連フラグを読み取る
        
        // 死亡による転送の場合はペナルティをスキップ
        if (isDeathRelated) {
            logger.info("死亡による転送のため夜間ログアウトペナルティをスキップ: " + uuid);
            return;
        }
        
        // 死亡フラグが立っている場合もスキップ
        if (deathFlagSet.contains(uuid)) {
            logger.info("死亡フラグが立っているため夜間ログアウトペナルティをスキップ: " + uuid);
            return;
        }
        
        long nightLogoutBanDuration = configManager.getInt("ban_after_night_logout_minutes", 10) * 60_000L;
        
        // 既存のBANがある場合は上書きしない（より長いペナルティを優先）
        BanInfo existingBan = banMap.get(uuid);
        if (existingBan != null) {
            long existingRemaining = existingBan.unbanTime - System.currentTimeMillis();
            long newBanTime = System.currentTimeMillis() + nightLogoutBanDuration;
            if (existingRemaining > nightLogoutBanDuration) {
                logger.info("既存のBANの方が長いため、夜間ログアウトペナルティを適用しません: " + uuid);
                return;
            }
        }
        
        // プレイヤー名を取得
        String playerName = "Unknown";
        Optional<Player> player = server.getPlayer(uuid);
        if (player.isPresent()) {
            playerName = player.get().getUsername();
        } else {
            // プレイヤーが既にオフラインの場合、既存のBANデータからユーザーネームを取得
            if (existingBan != null && existingBan.username != null) {
                playerName = existingBan.username;
            }
            // それでも見つからない場合は、過去のデータを確認
            logger.info("プレイヤーがオフラインのため、UUIDで識別: " + uuid);
        }
        
        // ユーザーネーム付きでBANを記録
        banMap.put(uuid, new BanInfo(
            System.currentTimeMillis() + nightLogoutBanDuration, 
            BanInfo.Reason.NIGHT_LOGOUT,
            playerName
        ));
        saveBans();
        
        logger.info("夜間ログアウトペナルティを適用: " + playerName + " (" + uuid + ")");
        
        // 他のサーバーのプレイヤーに通知（保存されたユーザーネームを使用）
        String message = String.format("§e%s が夜の地獄から逃げ出したため、%d分間のペナルティが課されました。", 
            playerName, nightLogoutBanDuration / 60_000L);
        broadcastToAllServers(message);
    }

    private void broadcastToAllServers(String message) {
        server.getAllPlayers().forEach(p -> p.sendMessage(Component.text(message)));
    }

    private void handleTimeChange(long time, String message) {
        Long lastTime = worldTimes.get("jigoku");
        worldTimes.put("jigoku", time);
        
        if (lastTime != null) {
            boolean wasNight = lastTime >= 12000 && lastTime < 24000;
            boolean isNight = time >= 12000 && time < 24000;
            if (isNight && !wasNight) {
                broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
            } else if (!isNight && wasNight) {
                broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
            }
        } else {
             broadcastToGense(message);
        }
    }

    private void handleTimeState(ByteArrayDataInput in) {
        String worldName = in.readUTF();
        long time = in.readLong();
        
        Long lastTime = worldTimes.get(worldName);
        worldTimes.put(worldName, time);

        if (lastTime != null && "jigoku".equals(worldName)) {
            boolean wasNight = lastTime >= 12000 && lastTime < 24000;
            boolean isNight = time >= 12000 && time < 24000;
            if (isNight && !wasNight) {
                broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
            } else if (!isNight && wasNight) {
                broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
            }
        }
    }

    private void handleHeartbeatResponse(ByteArrayDataInput in) {
        long time = in.readLong();
        Long lastTime = worldTimes.get("jigoku");
        worldTimes.put("jigoku", time);

        if (lastTime != null) {
            boolean wasNight = lastTime >= 12000 && lastTime < 24000;
            boolean isNight = time >= 12000 && time < 24000;
            if (isNight && !wasNight) {
                broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
            } else if (!isNight && wasNight) {
                broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
            }
        }
    }

    private boolean isJigokuNight() {
        if (mysqlEnabled) {
            try {
                String query = "SELECT is_night FROM world_times WHERE world_name = 'jigoku'";
                try (PreparedStatement stmt = mysqlConnection.prepareStatement(query);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("is_night");
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to check Jigoku night status from MySQL", e);
            }
        }
        
        // フォールバック: キャッシュから確認
        Long time = worldTimes.get("jigoku");
        return time != null && time >= 12000 && time < 24000;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String serverName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");

        // Genseサーバーに接続し、かつdeathフラグが立っている場合
        if ("gense".equals(serverName) && deathFlagSet.contains(uuid)) {
            // Genseに擬似死亡を依頼
            server.getServer("gense").ifPresent(genseServer -> {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("death_respawn");
                out.writeUTF(uuid.toString());
                genseServer.sendPluginMessage(CHANNEL, out.toByteArray());
            });
            // フラグを解除
            deathFlagSet.remove(uuid);
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getOriginalServer().getServerInfo().getName();
        UUID playerUuid = player.getUniqueId();

        // 夜間ログアウトのBANチェックのみ
        BanInfo banInfo = banMap.get(playerUuid);
        if (banInfo != null && banInfo.reason == BanInfo.Reason.NIGHT_LOGOUT) {
            long remainingSeconds = Math.max(0, (banInfo.unbanTime - System.currentTimeMillis()) / 1000);
            
            // 期限切れのBANは削除
            if (remainingSeconds <= 0) {
                banMap.remove(playerUuid);
                saveBans();
                return;
            }
            
            // 夜間ログアウトペナルティ中はすべてのサーバーへの接続を制限
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            long remainingMinutes = remainingSeconds / 60;
            String message = String.format("§c夜間ログアウトペナルティ: あと%d分%d秒待つ必要があります。", 
                remainingMinutes, remainingSeconds % 60);
            player.sendMessage(Component.text(message));
            return;
        }

        // Jigokuサーバーへの接続時の夜間チェック（BANされていない場合のみ）
        if (serverName.equals(configManager.getString("jigoku_server_name", "jigoku")) && isJigokuNight()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(Component.text("§c夜の地獄は危険すぎるため、移動できません。"));
        }
    }

    private String formatBanMessage(BanInfo.Reason reason, long remainingSeconds) {
        switch (reason) {
            case NIGHT_LOGOUT:
                return String.format("§c夜間にログアウトしたペナルティ中です。残り%d秒後にサーバーに接続できます。", remainingSeconds);
            default:
                return String.format("§cあなたは制限されています。残り%d秒", remainingSeconds);
        }
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
        String gense = configManager.getString("gense_server_name", "gense");
        server.getAllPlayers().stream()
            .filter(p -> p.getCurrentServer().isPresent()
                && p.getCurrentServer().get().getServerInfo().getName().equals(gense))
            .forEach(p -> p.sendMessage(Component.text(message)));
    }

    private void loadBans() {
        try {
            if (!banFile.exists() || banFile.length() == 0) return; // ファイルが存在しないか空なら何もしない
            banMap.clear();
            Map<String, BanInfo> tmp = mapper.readValue(banFile, new TypeReference<Map<String, BanInfo>>() {});
            tmp.forEach((k, v) -> banMap.put(UUID.fromString(k), v));
        } catch (Exception e) {
            logger.error("Failed to load bans from bans.json", e);
        }
    }

    private void saveBans() {
        try {
            Map<String, BanInfo> toSave = new HashMap<>();
            banMap.forEach((k, v) -> toSave.put(k.toString(), v));
            if (toSave.isEmpty()) {
                return; // 空の場合は書き込まない
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(banFile, toSave);
        } catch (Exception e) {
            logger.error("Failed to save bans to bans.json", e);
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
}
