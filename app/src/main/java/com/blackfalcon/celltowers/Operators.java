package com.blackfalcon.celltowers;

/** Small offline table of mobile operators (MCC-MNC). Unknown ones fall back to the code itself. */
public final class Operators {
    private Operators() { }

    public static String name(String mcc, String mnc) {
        if (mcc == null || mcc.length() == 0) return "Unknown";
        String m = mnc == null ? "" : mnc;
        while (m.length() > 1 && m.startsWith("0")) m = m.substring(1);
        String code = mcc + "-" + m;
        switch (code) {
            case "410-1": return "Jazz";
            case "410-3": return "Ufone";
            case "410-4": return "Zong";
            case "410-6": return "Telenor";
            case "410-7": return "Jazz";
            case "410-8": return "SCOM";
            default: return "MCC " + mcc + " / MNC " + (mnc == null ? "?" : mnc);
        }
    }
}
