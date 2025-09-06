package jp.example.gense;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.GameMode;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;

import jp.example.gense.HuskSyncHook;

public class GenseDeathRespawnListener extends JavaPlugin implements PluginMessageListener, Listener, CommandExecutor {

    private static final String CHANNEL = "myserver:bancontrol";
    private static final String DEATH_RESPAWN_SUBCHANNEL = "death_respawn";
    private static final String DEATH_SUBCHANNEL = "death";
    private static final String QUERY_GAMEMODE_SUBCHANNEL = "query_gamemode";
    private static final String GAMEMODE_RESPONSE_SUBCHANNEL = "gamemode_response";
    private static final String JIGOKU_TRANSFER_SUBCHANNEL = "jigoku_transfer";
    private static final String GAMEMODE_UPDATE_SUBCHANNEL = "gamemode_update";
    
    private HuskSyncHook huskSyncHook;

    @Override
    public void onEnable() {
        // HuskSync統合を初期化
        this.huskSyncHook = new HuskSyncHook(this);
        
        registerChannels();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();
        getLogger().info("GenseDeathRespawnListenerが有効になりました。");
    }

    private void registerChannels() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
    }

    private void registerCommands() {
        this.getCommand("jigoku").setExecutor(this);
        this.getCommand("adminjigoku").setExecutor(this);
        this.getCommand("jigokutime").setExecutor(this);
    }

    @Override
    public void onDisable() {
        getLogger().info("GenseDeathRespawnListenerが無効になりました。");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subChannel = in.readUTF();
            
            handlePluginMessage(subChannel, in);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "プラグインメッセージの処理中にエラーが発生しました", e);
        }
    }

    private void handlePluginMessage(String subChannel, ByteArrayDataInput in) {
        switch (subChannel) {
            case DEATH_RESPAWN_SUBCHANNEL:
                handleDeathRespawn(in);
                break;
            case DEATH_SUBCHANNEL:
                handleDeathMessage(in);
                break;
            case QUERY_GAMEMODE_SUBCHANNEL:
                handleGameModeQuery(in);
                break;
            default:
                getLogger().warning("未知のサブチャンネル: " + subChannel);
        }
    }

    private void handleDeathRespawn(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        Player targetPlayer = Bukkit.getPlayer(uuid);
        
        if (targetPlayer != null && targetPlayer.isOnline()) {
            triggerPseudoRespawn(targetPlayer);
        } else {
            getLogger().warning("死亡リスポーン要求を受信しましたが、プレイヤーが見つかりません: " + uuid);
        }
    }

    private void handleDeathMessage(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        String deathMessage = in.readUTF();
        
        // 死亡メッセージをブロードキャスト
        String formattedMessage = formatDeathMessage(deathMessage);
        Bukkit.broadcastMessage(formattedMessage);
    }

    private String formatDeathMessage(String deathMessage) {
        return "§c[地獄での死亡] " + deathMessage;
    }

    private void handleGameModeQuery(ByteArrayDataInput in) {
        UUID uuid = UUID.fromString(in.readUTF());
        Player queriedPlayer = Bukkit.getPlayer(uuid);
        
        sendGameModeResponse(uuid, queriedPlayer);
    }

    private void sendGameModeResponse(UUID uuid, Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(GAMEMODE_RESPONSE_SUBCHANNEL);
        out.writeUTF(uuid.toString());
        
        if (player != null && player.isOnline()) {
            out.writeUTF(player.getGameMode().name());
        } else {
            out.writeUTF("UNKNOWN");
        }
        
        sendPluginMessage(out.toByteArray());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("jigoku")) {
            // ゲームモード制限を削除
            requestJigokuTransfer(player);
            return true;
        } else if (command.getName().equalsIgnoreCase("adminjigoku")) {
            // OP権限チェック
            if (!player.isOp()) {
                player.sendMessage("§cこのコマンドを実行する権限がありません。");
                return true;
            }
            
            // 管理者用の地獄転送
            requestAdminJigokuTransfer(player);
            return true;
        } else if (command.getName().equalsIgnoreCase("jigokutime")) {
            // Velocityに時刻情報をリクエスト
            requestJigokuTime(player);
            return true;
        }
        
        return false;
    }

    private void requestJigokuTransfer(Player player) {
        player.sendMessage("§c地獄への転送を開始します...");
        
        // HuskSyncでプレイヤーデータを保存してから転送
        huskSyncHook.savePlayerDataAndThen(player, () -> {
            // データ保存完了後にVelocityに転送リクエストを送信
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(JIGOKU_TRANSFER_SUBCHANNEL);
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            
            getLogger().info("HuskSyncデータ保存完了後、地獄転送を実行: " + player.getName());
        });
    }

    private void requestAdminJigokuTransfer(Player player) {
        player.sendMessage("§a[管理者] 地獄への強制転送を開始します...");
        
        // HuskSyncでプレイヤーデータを保存してから転送
        huskSyncHook.savePlayerDataAndThen(player, () -> {
            // データ保存完了後にVelocityに転送リクエストを送信
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("admin_jigoku_transfer");
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            
            getLogger().info("HuskSyncデータ保存完了後、管理者地獄転送を実行: " + player.getName());
        });
        getLogger().info(String.format("[管理者転送] %s が地獄への強制転送を実行しました。", player.getName()));
    }

    private void requestJigokuTime(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("query_jigoku_time");
        out.writeUTF(player.getUniqueId().toString());
        player.sendPluginMessage(this, CHANNEL, out.toByteArray());
        player.sendMessage("§e地獄ワールドの時刻を確認中...");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // プレイヤー参加時にゲームモードをVelocityに通知
        sendGameModeUpdate(event.getPlayer());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        // ゲームモード変更時にVelocityに通知
        sendGameModeUpdate(event.getPlayer());
    }

    private void sendGameModeUpdate(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(GAMEMODE_UPDATE_SUBCHANNEL);
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(player.getGameMode().name());
        
        sendPluginMessage(out.toByteArray());
    }

    private void sendPluginMessage(byte[] data) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        
        if (!players.isEmpty()) {
            // 最初のプレイヤーを使用してメッセージを送信
            players.iterator().next().sendPluginMessage(this, CHANNEL, data);
        } else {
            getLogger().warning("プラグインメッセージを送信できません：オンラインプレイヤーがいません");
        }
    }

    // プレイヤーを強制的に死亡させ、即座にリスポーンさせるメソッド
    private void triggerPseudoRespawn(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            player.sendMessage("§c地獄での死の代償を支払う...");

            // 死亡処理
            try {
                player.setHealth(0.0);
            } catch (Exception e) {
                getLogger().warning("プレイヤーの体力を0に設定できませんでした: " + e.getMessage());
                return;
            }

            // リスポーン処理
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    player.spigot().respawn();
                } catch (Exception e) {
                    getLogger().warning("player.spigot().respawn()が利用できません。" +
                        "サーバーがSpigot/Paper/Purpurなどであることを確認してください。");
                    // フォールバック：手動でリスポーンイベントを発火
                    player.teleport(player.getWorld().getSpawnLocation());
                }
            }, 1L);
        });
    }
}
