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
        try {
            Files.createDirectories(dataDirectory);
            this.banFile = dataDirectory.resolve("bans.json").toFile();
            if (banFile.createNewFile()) {
                logger.info("Created a new bans.json file.");
            }
        } catch (IOException e) {
            logger.error("Failed to create the ban file.", e);
        }

        loadBans();
        startCleanupTask();

        // コマンド、イベント、チャンネルを登録
        server.getCommandManager().register(server.getCommandManager().metaBuilder("unban").build(), new UnbanCommand());
        // server.getCommandManager().register(server.getCommandManager().metaBuilder("jigoku").build(), new JigokuCommand()); // Gense側で処理するため削除
        server.getCommandManager().register(server.getCommandManager().metaBuilder("gense").build(), new GenseCommand());
        server.getChannelRegistrar().register(CHANNEL);

        // 定期的にJigokuサーバーの時刻を確認するタスクを開始
        if (mysqlEnabled) {
            startMySQLHeartbeatTask();
        } else {
            startHeartbeatTask();
        }
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

    // プラグインメッセージを常にByteStreamsでラップして処理する
    ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
    String subChannel = in.readUTF();
    UUID uuid;

    switch (subChannel) {
        case "jigoku_transfer":
            uuid = UUID.fromString(in.readUTF());
            server.getPlayer(uuid).ifPresent(player -> {
                long jigokuTime = worldTimes.getOrDefault("jigoku", -1L);
                boolean isNight = jigokuTime >= 12000 && jigokuTime < 24000;

                if (isNight) {
                    player.sendMessage(Component.text("§c夜の地獄は危険すぎるため、移動できません。"));
                    return;
                }

                server.getServer(configManager.getString("jigoku_server_name", "jigoku")).ifPresent(target -> {
                    player.createConnectionRequest(target).fireAndForget();
                });
            });
            break;

        case "gamemode_update":
            uuid = UUID.fromString(in.readUTF());
            String gameMode = in.readUTF();
            gameModeCache.put(uuid, gameMode);
            break;

        case "death":
            uuid = UUID.fromString(in.readUTF());
            banMap.put(uuid, new BanInfo(System.currentTimeMillis() + configManager.getInt("ban_after_death_minutes", 5) * 60_000, BanInfo.Reason.DEATH));
            saveBans();
            deathFlagSet.add(uuid);

            // Genseに死亡情報を転送
            server.getServer("gense").ifPresent(gense ->
                gense.sendPluginMessage(event.getIdentifier(), event.getData())
            );

            // 即座にGenseサーバーへ転送する処理を再度有効化
            server.getPlayer(uuid).ifPresent(player ->
                server.getServer("gense").ifPresent(target ->
                    player.createConnectionRequest(target).fireAndForget()
                )
            );
            break;

        case "night_logout":
            uuid = UUID.fromString(in.readUTF());
            banMap.put(uuid, new BanInfo(System.currentTimeMillis() + configManager.getInt("ban_after_night_logout_minutes", 5) * 60_000, BanInfo.Reason.NIGHT_LOGOUT));
            saveBans();
            break;

        case "jigoku_night":
            worldTimes.put("jigoku", 13000L); // 夜の時刻として13000を設定
            broadcastToGense(configManager.getString("jigoku_night_message", "何処かから地鳴りが聞こえる…（地獄ワールドが夜になりました）"));
            break;

        case "jigoku_day":
            worldTimes.put("jigoku", 1000L); // 昼の時刻として1000を設定
            broadcastToGense(configManager.getString("jigoku_day_message", "地獄ワールドの夜は明けました。今なら安全に移動できます！"));
            break;

        case "gamemode_response":
            uuid = UUID.fromString(in.readUTF());
            String responseGameMode = in.readUTF();
            // pendingGameModeQueriesの処理は非同期クエリ時に必要
            if (pendingGameModeQueries.containsKey(uuid)) {
                pendingGameModeQueries.get(uuid).complete(responseGameMode);
            }
            // レスポンスでもキャッシュを更新
            gameModeCache.put(uuid, responseGameMode);
            break;

        case "time_response":
            uuid = UUID.fromString(in.readUTF());
            long responseTime = in.readLong(); // 変数名を変更
            worldTimes.put("jigoku", responseTime);
            // pendingTimeQueriesの処理
            if (pendingTimeQueries.containsKey(uuid)) {
                pendingTimeQueries.get(uuid).complete(responseTime);
            }
            break;

        case "heartbeat_response":
            String state = in.readUTF();
            long heartbeatTime = in.readLong(); // 変数名を変更
            worldTimes.put("jigoku", heartbeatTime);
            
            // 状態に応じてメッセージを送信
            if (state.equals("jigoku_night")) {
                worldTimes.put("jigoku", Math.max(12000L, heartbeatTime)); // 夜の時刻を保証
            } else {
                worldTimes.put("jigoku", Math.min(11999L, heartbeatTime)); // 昼の時刻を保証
            }
            break;

        case "jigoku_time_response":
            uuid = UUID.fromString(in.readUTF());
            String worldName = in.readUTF();
            long jigokuResponseTime = in.readLong(); // 変数名を変更
            worldTimes.put("jigoku", jigokuResponseTime);
            // pendingTimeQueriesの処理
            if (pendingTimeQueries.containsKey(uuid)) {
                pendingTimeQueries.get(uuid).complete(jigokuResponseTime);
            }
            break;
        
        case "gense_transfer":
            uuid = UUID.fromString(in.readUTF());
            server.getPlayer(uuid).ifPresent(player -> {
                // BANチェック
                if (banMap.containsKey(uuid)) {
                    BanInfo info = banMap.get(uuid);
                    long remainingSeconds = Math.max(0, (info.unbanTime - System.currentTimeMillis()) / 1000);
                    player.sendMessage(Component.text(String.format("§cあなたは地獄から追放されています。残り%d秒", remainingSeconds)));
                    return;
                }

                server.getServer(configManager.getString("gense_server_name", "gense")).ifPresent(target -> {
                    player.createConnectionRequest(target).fireAndForget();
                });
            });
            break;

        // 他のcaseは省略
    }
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

    // BAN状態の確認
    if (banMap.containsKey(playerUuid)) {
        BanInfo info = banMap.get(playerUuid);
        long remainingSeconds = Math.max(0, (info.unbanTime - System.currentTimeMillis()) / 1000);
        String jigokuServerName = configManager.getString("jigoku_server_name", "jigoku");
        String genseServerName = configManager.getString("gense_server_name", "gense");

        // 理由を問わず、BANされているプレイヤーはJigokuサーバーへ移動できない
        if (serverName.equals(jigokuServerName)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(Component.text(String.format("§cあなたは地獄から追放されています。残り%d秒", remainingSeconds)));
            return;
        }

        // 死亡BANの場合
        if (info.reason == BanInfo.Reason.DEATH) {
            // Genseサーバー以外への接続は許可しない
            if (!serverName.equals(genseServerName)) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.disconnect(Component.text("§c死亡ペナルティ中です。他のサーバーには移動できません。現世で罪を償ってください。"));
                return;
            }
        } else { // 死亡以外の理由でのBAN
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(Component.text("あなたはBANされています。理由: " + info.reason.toString()));
            return;
        }
    }

    // Jigokuサーバーへの接続時、MySQLから時刻を取得して夜間チェック
    if (serverName.equals(configManager.getString("jigoku_server_name", "jigoku")) && mysqlEnabled) {
        try {
            String query = "SELECT is_night FROM world_times WHERE world_name = 'jigoku'";
            try (PreparedStatement stmt = mysqlConnection.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {
                
                if (rs.next() && rs.getBoolean("is_night")) {
                    event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    player.sendMessage(Component.text("§c夜の地獄は危険すぎるため、移動できません。"));
                    return;
                }
            }
        } catch (SQLException e) {
            logger.error("MySQLから時刻情報の取得に失敗しました。", e);
        }
    }
}

private CompletableFuture<String> queryGameMode(Player player) {
    CompletableFuture<String> future = new CompletableFuture<>();
    UUID uuid = player.getUniqueId();
    pendingGameModeQueries.put(uuid, future);

    server.getServer("gense").ifPresent(genseServer -> {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("query_gamemode");
        out.writeUTF(uuid.toString());
        genseServer.sendPluginMessage(CHANNEL, out.toByteArray());
    });

    // タイムアウト処理
    scheduler.schedule(() -> {
        if (!future.isDone()) {
            future.complete("TIMEOUT"); // タイムアウト時は"TIMEOUT"を返す
            pendingGameModeQueries.remove(uuid);
        }
    }, 2, TimeUnit.SECONDS);

    future.whenComplete((result, error) -> pendingGameModeQueries.remove(uuid));

    return future;
}

private CompletableFuture<Long> queryJigokuTime(Player player) {
    CompletableFuture<Long> future = new CompletableFuture<>();
    UUID uuid = player.getUniqueId();
    // Jigokuの時刻応答はプレイヤーを特定できないため、リクエストごとにFutureを管理する
    pendingTimeQueries.put(uuid, future);


    server.getServer("jigoku").ifPresent(jigokuServer -> {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("get_time");
        out.writeUTF(player.getUniqueId().toString()); // プレイヤーのUUIDを送信
        out.writeUTF("world"); // Jigokuのメインワールド名を指定
        jigokuServer.sendPluginMessage(CHANNEL, out.toByteArray());
    });

    // タイムアウト処理
    scheduler.schedule(() -> {
        if (!future.isDone()) {
            // タイムアウトした場合は、キャッシュされた最後の時刻情報を使う
            future.complete(worldTimes.getOrDefault("world", 0L));
            pendingTimeQueries.remove(uuid);
        }
    }, 2, TimeUnit.SECONDS);
    
    // 応答が来たら完了させる処理はonPluginMessageにある
    // ただし、現在の実装ではUUIDが送られてこないので、ブロードキャスト的に全pendingが完了してしまう
    // これはJigoku側の修正が必要だが、ひとまずこの形で進める
    future.whenComplete((result, error) -> pendingTimeQueries.remove(uuid));


    return future;
}


private void cachePlayerGameMode(UUID uuid, String gameMode) {
    gameModeCache.put(uuid, gameMode);
    scheduler.schedule(() -> gameModeCache.remove(uuid), 1, TimeUnit.MINUTES);
}

private String getPlayerGameMode(UUID uuid) {
    return gameModeCache.get(uuid);
}

private void queryPlayerGameModeFromGense(Player player, ServerPreConnectEvent event) {
    player.getCurrentServer().ifPresent(server -> {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("query_gamemode");
        server.sendPluginMessage(CHANNEL, out.toByteArray());
    });

    // 一時的に接続を保留する (遅延処理が必要)
    CompletableFuture.runAsync(() -> {
        try {
            Thread.sleep(1000); // Genseの応答を待つ（調整可）
        } catch (InterruptedException ignored) {}

        // レスポンスを受信して処理されているかを確認
        String gameMode = getPlayerGameMode(player.getUniqueId()); // 一時的保存から取得

        if ("SPECTATOR".equals(gameMode)) {
            player.sendMessage(Component.text("§c残機がありません。Jigokuには接続できません。"));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(event.getOriginalServer()));
        }
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
