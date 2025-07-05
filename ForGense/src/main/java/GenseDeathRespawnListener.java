package jp.example.gense;

import org.bukkit.Bukkit;
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

import java.util.UUID;

public class GenseDeathRespawnListener extends JavaPlugin implements PluginMessageListener, Listener {

    private static final String CHANNEL = "myserver:bancontrol";

    @Override
    public void onEnable() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("GenseDeathRespawnListenerが有効になりました。");
    }

    @Override
    public void onDisable() {
        getLogger().info("GenseDeathRespawnListenerが無効になりました。");
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
                    // プレイヤーを強制的に死亡させ、PlayerDeathEventをトリガーする
                    forceKillPlayer(targetPlayer);
                }
                break;

            case "death":
                // Jigokuからの死亡メッセージをブロードキャスト
                // メッセージ形式をVelocityの転送に合わせて調整
                uuid = UUID.fromString(in.readUTF()); // uuidを読み飛ばす
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
                    out.writeUTF(queriedPlayer.getGameMode().name()); // 現在のゲームモードをそのまま返す
                    
                    // メッセージはVelocityから来たので、任意のプレイヤー経由で返信
                    if (!Bukkit.getOnlinePlayers().isEmpty()) {
                       Bukkit.getOnlinePlayers().iterator().next().sendPluginMessage(this, CHANNEL, out.toByteArray());
                    }
                }
                break;
        }
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
        out.writeUTF("gamemode_update");
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(player.getGameMode().name());
        player.sendPluginMessage(this, CHANNEL, out.toByteArray());
    }

    // プレイヤーを強制的に死亡させるメソッド
    private void forceKillPlayer(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            player.sendMessage("§c地獄での死の代償を支払う...");
            player.setHealth(0.0);
        });
    }
}
