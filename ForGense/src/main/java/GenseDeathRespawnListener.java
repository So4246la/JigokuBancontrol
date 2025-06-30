package jp.example.gense;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

public class GenseDeathRespawnListener extends JavaPlugin implements PluginMessageListener {

    private static final String CHANNEL = "myserver:bancontrol";

    @Override
    public void onEnable() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
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
                triggerFakeRespawnEvent(player);
                break;

            case "death":
                String uuid = in.readUTF();
                String deathMessage = in.readUTF();
                Bukkit.broadcastMessage("§c[地獄での死亡] " + deathMessage);
                break;
        }
    }

    private void triggerFakeRespawnEvent(Player player) {
        Location respawnLocation = player.getLocation();
        PlayerRespawnEvent fakeRespawnEvent = new PlayerRespawnEvent(player, respawnLocation, false);
        Bukkit.getPluginManager().callEvent(fakeRespawnEvent);
    }
} 
