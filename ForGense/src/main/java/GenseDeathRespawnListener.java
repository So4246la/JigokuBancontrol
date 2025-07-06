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

import java.util.UUID;

public class GenseDeathRespawnListener extends JavaPlugin implements PluginMessageListener, Listener, CommandExecutor {

    private static final String CHANNEL = "myserver:bancontrol";

    @Override
    public void onEnable() {
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("jigoku").setExecutor(this); // コマンドを登録
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
                    // プレイヤーを強制的に死亡させ、即座にリスポーンさせる
                    triggerPseudoRespawn(targetPlayer);
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
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("gamemode_response");
                out.writeUTF(uuid.toString());

                if (queriedPlayer != null) {
                    // プレイヤーがGenseサーバーにいれば、そのゲームモードを返す
                    out.writeUTF(queriedPlayer.getGameMode().name());
                } else {
                    // プレイヤーがGenseサーバーにいなければ、「不明」を意味する情報を返す
                    out.writeUTF("UNKNOWN");
                }
                // メッセージを送信してきたプロキシ経由で返信する
                // Genseに誰もいなくても、このチャンネルのリスナーはProxyなのでメッセージは届く
                getServer().sendPluginMessage(this, CHANNEL, out.toByteArray());
                break;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        Player player = (Player) sender;

        // スペクテイターモードのプレイヤーはコマンドを実行できない
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage("§cスペクテイターモードでは地獄に移動できません。");
            return true;
        }

        // Velocityにサーバー移動を依頼する
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("jigoku_transfer");
        out.writeUTF(player.getUniqueId().toString());
        player.sendPluginMessage(this, CHANNEL, out.toByteArray());

        return true;
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

    // プレイヤーを強制的に死亡させ、即座にリスポーンさせるメソッド
    private void triggerPseudoRespawn(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            player.sendMessage("§c地獄での死の代償を支払う...");

            // 実際の死亡処理を再度有効化
            player.setHealth(0.0);

            // 少し遅延させてからリスポーンを実行し、イベントが適切に処理されるようにする
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    player.spigot().respawn();
                } catch (Exception e) {
                    getLogger().warning("player.spigot().respawn()が利用できません。サーバーがSpigot/Paper/Purpurなどであることを確認してください。");
                }
            }, 1L); // 1tick後
        });
    }
}
