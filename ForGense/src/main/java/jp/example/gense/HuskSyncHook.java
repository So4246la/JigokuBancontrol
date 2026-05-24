package jp.example.gense;

import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * HuskSync APIとの統合を管理するクラス
 */
public class HuskSyncHook {

    private static final long SAVE_TIMEOUT_TICKS = 100L; // 5 秒 (20 tick/秒)

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
        final AtomicBoolean fired = new AtomicBoolean(false);
        Runnable safeCallback = () -> {
            if (fired.compareAndSet(false, true)) {
                plugin.getServer().getScheduler().runTask(plugin, callback);
            }
        };

        // 救命タイムアウト: SAVE_TIMEOUT_TICKS 経過してもコールバックが発火しなければ強制的に呼ぶ
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!fired.get()) {
                plugin.getLogger().warning("HuskSync 保存処理がタイムアウトしました。転送を続行します: " + player.getName());
            }
            safeCallback.run();
        }, SAVE_TIMEOUT_TICKS);

        if (!isEnabled()) {
            plugin.getLogger().warning("HuskSyncが利用できないため、データ保存をスキップします: " + player.getName());
            safeCallback.run();
            return;
        }

        try {
            final BukkitUser bukkitUser = huskSyncAPI.getUser(player);
            plugin.getLogger().info("プレイヤーデータの非同期取得を開始: " + player.getName());

            huskSyncAPI.getCurrentData(bukkitUser)
                .whenComplete((optionalSnapshot, throwable) -> {
                    try {
                        if (throwable != null) {
                            plugin.getLogger().log(Level.WARNING, "HuskSyncデータの取得処理でエラーが発生しました: " + player.getName(), throwable);
                            safeCallback.run();
                            return;
                        }

                        if (optionalSnapshot.isPresent()) {
                            final DataSnapshot.Unpacked snapshotToSave = optionalSnapshot.get();
                            plugin.getLogger().info("データの取得が完了。保存処理に移行します: " + player.getName());

                            try {
                                huskSyncAPI.addSnapshot(bukkitUser, snapshotToSave, (savedUser, savedSnapshot) -> {
                                    plugin.getLogger().info("プレイヤーデータの保存が完了しました: " + savedUser.getUsername());
                                    safeCallback.run();
                                });
                            } catch (Throwable t) {
                                plugin.getLogger().log(Level.WARNING, "HuskSync addSnapshot 呼び出しに失敗: " + player.getName(), t);
                                safeCallback.run();
                            }
                        } else {
                            plugin.getLogger().warning("保存対象のHuskSyncデータが見つかりませんでした: " + player.getName());
                            safeCallback.run();
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "HuskSync whenComplete ハンドラで予期せぬエラー: " + player.getName(), t);
                        safeCallback.run();
                    }
                });

        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "HuskSync処理の準備中にエラーが発生: " + player.getName(), t);
            safeCallback.run();
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
