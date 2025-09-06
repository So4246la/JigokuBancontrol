package jp.example.gense;

import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Level;

/**
 * HuskSync APIとの統合を管理するクラス
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
     * プレイヤーのデータを手動保存し、完了後にコールバックを実行
     * 
     * @param player 保存するプレイヤー
     * @param callback 保存完了後に実行するコールバック
     */
    public void savePlayerDataAndThen(Player player, Runnable callback) {
        if (!isEnabled()) {
            plugin.getLogger().warning("HuskSyncが利用できないため、データ保存をスキップします: " + player.getName());
            callback.run();
            return;
        }
        
        try {
            // BukkitUserを取得
            BukkitUser bukkitUser = huskSyncAPI.getUser(player);
            plugin.getLogger().info("プレイヤーデータの保存を開始: " + player.getName());
            
            // データスナップショットを作成
            huskSyncAPI.createSnapshot(bukkitUser);
            plugin.getLogger().info("プレイヤーデータの保存が完了: " + player.getName());
            
            // メインスレッドでコールバックを実行
            plugin.getServer().getScheduler().runTask(plugin, callback);
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, 
                "HuskSyncデータ保存処理でエラーが発生: " + player.getName(), e);
            callback.run();
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
