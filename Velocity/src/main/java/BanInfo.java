package jp.example.bancontrol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

public class BanInfo implements Serializable {
    public enum Reason { DEATH, NIGHT_LOGOUT }
    
    @JsonProperty("unbanTime")
    public long unbanTime;
    
    @JsonProperty("reason")
    public Reason reason;
    
    @JsonProperty("username")
    public String username;
    
    // デフォルトコンストラクタ（Jacksonのデシリアライズに必要）
    public BanInfo() {
    }
    
    // 既存のコンストラクタ（後方互換性のため残す）
    @JsonCreator
    public BanInfo(@JsonProperty("unbanTime") long unbanTime, 
                   @JsonProperty("reason") Reason reason) {
        this.unbanTime = unbanTime;
        this.reason = reason;
        this.username = "Unknown";
    }
    
    // ユーザーネーム付きの新しいコンストラクタ
    public BanInfo(long unbanTime, Reason reason, String username) {
        this.unbanTime = unbanTime;
        this.reason = reason;
        this.username = username != null ? username : "Unknown";
    }
}
