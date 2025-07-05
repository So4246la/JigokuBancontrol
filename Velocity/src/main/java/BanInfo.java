package jp.example.bancontrol;

import java.io.Serializable;

public class BanInfo implements Serializable {
    public enum Reason { DEATH, NIGHT_LOGOUT }
    public long unbanTime;
    public Reason reason;
    public BanInfo(long unbanTime, Reason reason) {
        this.unbanTime = unbanTime;
        this.reason = reason;
    }
}
