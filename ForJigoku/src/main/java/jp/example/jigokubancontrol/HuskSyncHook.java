package jp.example.jigokubancontrol;

import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Optional;
import java.util.logging.Level;

/**
 * HuskSync APIとの統合を管理するクラス（Gense側と同等のシンプル版）
 */
public class HuskSyncHook {

    private final JavaPlugin plugin;
    private BukkitHuskSyncAPI huskSyncAPI;
    private boolean enabled = false;

    public HuskSyncHook(JavaPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        try {
            // HuskSyncプラグインの存在を確認
            if (plugin.getServer().getPluginManager().getPlugin("HuskSync") == null) {
                plugin.getLogger().info("HuskSyncプラグインが見つかりません。HuskSync統合は無効になります。");
                return;
            }

            // BukkitHuskSyncAPIのインスタンスを取得
            this.huskSyncAPI = BukkitHuskSyncAPI.getInstance();
            this.enabled = true;

            plugin.getLogger().info("HuskSync APIとの統合が正常に初期化されました。");

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "HuskSync APIの初期化に失敗しました。", e);
            this.enabled = false;
        }
    }

    /**
     * HuskSync統合が有効かどうかを確認
     */
    public boolean isEnabled() {
        return enabled && huskSyncAPI != null;
    }

    /**
     * プレイヤーの現在のデータをHuskSyncから取得して保存し、完了後にコールバックを実行します。
     *
     * @param player   保存するプレイヤー
     * @param callback 保存完了後にメインスレッドで実行するコールバック
     */
    public void savePlayerDataAndThen(Player player, Runnable callback) {
        if (!isEnabled()) {
            plugin.getLogger().warning("HuskSyncが利用できないため、データ保存をスキップします: " + player.getName());
            plugin.getServer().getScheduler().runTask(plugin, callback);
            return;
        }

        try {
            final BukkitUser bukkitUser = huskSyncAPI.getUser(player);
            plugin.getLogger().info("プレイヤーデータの非同期取得を開始: " + player.getName());

            // 1. getCurrentDataで非同期にデータを取得する
            huskSyncAPI.getCurrentData(bukkitUser)
                .whenComplete((optionalSnapshot, throwable) -> {
                    // このブロックはgetCurrentDataの処理が完了した後に実行されます

                    // 2. データ取得中にエラーが発生した場合の処理
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "HuskSyncデータの取得処理でエラーが発生しました: " + player.getName(), throwable);
                        // エラーが発生した場合も、コールバックは実行する
                        plugin.getServer().getScheduler().runTask(plugin, callback);
                        return;
                    }

                    // 3. 取得したデータ(Optional)を処理する
                    if (optionalSnapshot.isPresent()) {
                        // データが見つかった場合
                        final DataSnapshot.Unpacked snapshotToSave = optionalSnapshot.get();
                        plugin.getLogger().info("データの取得が完了。保存処理に移行します: " + player.getName());

                        // 4. 取得したスナップショットをaddSnapshotで保存する
                        huskSyncAPI.addSnapshot(bukkitUser, snapshotToSave, (savedUser, savedSnapshot) -> {
                            // このブロックはaddSnapshotの保存が完了した後に実行されます
                            plugin.getLogger().info("プレイヤーデータの保存が完了しました: " + savedUser.getUsername());

                            // 5. 最終的なコールバックをメインスレッドで実行
                            plugin.getServer().getScheduler().runTask(plugin, callback);
                        });
                    } else {
                        // データが見つからなかった場合
                        plugin.getLogger().warning("保存対象のHuskSyncデータが見つかりませんでした: " + player.getName());
                        // データが無くても、一連の処理は完了したとみなしコールバックを実行
                        plugin.getServer().getScheduler().runTask(plugin, callback);
                    }
                });

        } catch (Exception e) {
            // 同期的なAPI呼び出し(getUserなど)でエラーが発生した場合
            plugin.getLogger().log(Level.WARNING, "HuskSync処理の準備中にエラーが発生: " + player.getName(), e);
            plugin.getServer().getScheduler().runTask(plugin, callback);
        }
    }

    /**
     * プレイヤーのデータ保存を強制実行（同期的）
     * 注意: メインスレッドをブロックする可能性があります
     */
    public void forceUpdateUserData(Player player) {
        if (!isEnabled()) {
            return;
        }

        try {
            BukkitUser bukkitUser = huskSyncAPI.getUser(player);
            // ユーザーデータを最新の状態に更新
            // Note: v3.6.4 では直接的なupdateUserDataメソッドは廃止されている可能性があります
            // 代わりにcreatSnapshotを使用
            huskSyncAPI.createSnapshot(bukkitUser);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                "HuskSyncユーザーデータ更新でエラーが発生: " + player.getName(), e);
        }
    }
}
