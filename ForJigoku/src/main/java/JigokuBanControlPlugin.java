package jp.example.jigokubancontrol;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import java.util.Random;
import java.util.UUID;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class JigokuBanControlPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    private static final String CHANNEL = "myserver:bancontrol";

    private boolean isNight(World world) {
        long time = world.getTime();
        return time >= 12000 && time < 24000;
    }

    @Override
    public void onEnable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("JigokuBanControlが有効になりました。");
    }

@EventHandler
public void onPlayerDeath(PlayerDeathEvent event) {
    Player player = event.getEntity();
    String deathMessage = event.getDeathMessage();
    UUID playerUUID = player.getUniqueId();

    ByteArrayDataOutput out = ByteStreams.newDataOutput();
    out.writeUTF("death");
    out.writeUTF(playerUUID.toString()); // ← BAN処理用
    out.writeUTF(deathMessage);          // ← デスログ転送用に追加

    player.sendPluginMessage(this, CHANNEL, out.toByteArray());
}

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isNight(player.getWorld())) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DataOutputStream data = new DataOutputStream(out)) {
                data.writeUTF("night_logout");
                data.writeUTF(player.getUniqueId().toString());
                player.sendPluginMessage(this, CHANNEL, out.toByteArray());
            } catch (IOException e) {
                getLogger().warning("夜間ログアウト通知の送信中にエラーが発生しました。");
                e.printStackTrace();
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        
        // ランダムな座標を生成 (X: -1000～1000, Z: -1000～1000)
        Random random = new Random();
        int x = random.nextInt(2001) - 1000; // -1000 to 1000
        int z = random.nextInt(2001) - 1000; // -1000 to 1000
        int y = world.getHighestBlockYAt(x, z) + 1; // 地表の高さ + 1
        
        Location randomLocation = new Location(world, x + 0.5, y, z + 0.5);
        
        // WorldBorderをランダムに設定
        setRandomWorldBorder(world, randomLocation, random);
        
        // プレイヤーをランダムな座標にテレポート
        Bukkit.getScheduler().runTask(this, () -> {
            player.teleport(randomLocation);
            player.sendMessage("§e地獄の世界へようこそ！ランダムな場所にスポーンしました。");
        });
    }
    
    private void setRandomWorldBorder(World world, Location center, Random random) {
        // ワールドボーダーのサイズをランダムに設定 (500～2000ブロック)
        double borderSize = 500 + random.nextDouble() * 1500; // 500 to 2000
        
        // ワールドボーダーの中心をスポーン地点に設定
        world.getWorldBorder().setCenter(center.getX(), center.getZ());
        world.getWorldBorder().setSize(borderSize);
        
        // ワールドボーダーの警告距離とダメージを設定
        world.getWorldBorder().setWarningDistance(50);
        world.getWorldBorder().setDamageAmount(1.0);
        world.getWorldBorder().setDamageBuffer(5.0);
        
        getLogger().info("ワールドボーダーを設定: 中心(" + center.getX() + ", " + center.getZ() + "), サイズ: " + borderSize);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        // "death_respawn" サブチャンネル受信時の処理
        // この処理はGenseサーバー側で行うため、Jigokuサーバーでは不要

        // 既存の get_time 処理
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(message))) {
            String request = in.readUTF();
            if ("get_time".equals(request)) {
                String worldName = in.readUTF();
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    long time = world.getTime();
                    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                         DataOutputStream data = new DataOutputStream(out)) {
                        data.writeUTF("time_response");
                        data.writeUTF(worldName);
                        data.writeLong(time);
                        player.sendPluginMessage(this, CHANNEL, out.toByteArray());
                    }
                }
            }
        } catch (IOException e) {
            getLogger().warning("PluginMessage受信中にエラーが発生しました。");
            e.printStackTrace();
        }
    }

    // 擬似リスポーンイベントを手動で呼ぶ
    private void triggerFakeRespawnEvent(Player player) {
        // 現在位置をそのままリスポーン先とする
        Location respawnLocation = player.getLocation();

        // 疑似的にPlayerRespawnEventを呼び出す
        PlayerRespawnEvent fakeRespawnEvent = new PlayerRespawnEvent(player, respawnLocation, false);
        Bukkit.getPluginManager().callEvent(fakeRespawnEvent);
    }
}
