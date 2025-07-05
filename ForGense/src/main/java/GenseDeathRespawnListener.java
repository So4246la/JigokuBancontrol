package jp.example.gense;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;


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

    switch (sub) {
        case "death_respawn":
            // プレイヤーを強制的に死亡状態にする
            forceKillPlayer(player);
            break;

        case "death":
            String uuid = in.readUTF();
            String deathMessage = in.readUTF(); // deathMessageも読み込む
            Bukkit.broadcastMessage("§c[地獄での死亡] " + deathMessage);
            break;

        case "query_gamemode":
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("gamemode_response");
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(player.getGameMode().name()); // ゲームモードを送信
            player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            break;
    }
}

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendGameModeUpdate(event.getPlayer());
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
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
    // 安全にプレイヤーを即死させ、通常の死亡処理をトリガーする
    Bukkit.getScheduler().runTask(this, () -> {
        player.setHealth(0.0);
    });
  }
}
