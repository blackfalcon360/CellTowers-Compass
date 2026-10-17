package com.blackfalcon.celltowers;

/** One cell (tower sector) as reported by the phone's modem. */
public final class Tower {
    public String tech = "?";          // 2G / 3G / 4G / 5G / CDMA
    public String mcc = "", mnc = "";
    public long area = -1;             // LAC (2G/3G) or TAC (4G/5G)
    public long cid = -1;              // cell id
    public int pci = -1;               // physical cell id / scrambling code
    public int chan = -1;              // ARFCN / UARFCN / EARFCN / NR-ARFCN
    public int dbm = Integer.MAX_VALUE;
    public boolean registered;         // true = the phone is connected to this one
    public String operator = "";

    public double lat = Double.NaN, lon = Double.NaN;
    public int rangeM = -1;
    public float distM = -1f, bearing = 0f;

    public String key() { return tech + ":" + mcc + ":" + mnc + ":" + area + ":" + cid + ":" + pci; }

    public boolean idValid() {
        return cid > 0 && area > 0 && mcc.length() > 0 && mnc.length() > 0;
    }

    /** OpenCelliD has no useful 5G data, so only 2G/3G/4G are looked up. */
    public boolean lookupable() { return idValid() && !tech.equals("5G") && !tech.equals("CDMA"); }

    public boolean hasPos() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    public boolean hasDbm() { return dbm != Integer.MAX_VALUE && dbm < 0; }
}
