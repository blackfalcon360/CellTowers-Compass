package com.blackfalcon.celltowers;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int GREEN = 0xFF2ECC71, GRAY = 0xFF9E9E9E, CARD = 0xFF0C0C1E, EDGE = 0xFF3A3A60,
            AMBER = 0xFFFFB300, BLUE = 0xFF4FC3F7, RED = 0xFFE57373;

    private TelephonyManager tm;
    private LocationManager lm;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private List<Tower> towers = new ArrayList<>();
    private final Map<String, double[]> found = new ConcurrentHashMap<>();   // key -> {lat, lon, range}; NaN = not in database
    private final Set<String> pending = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile String lookupStatus = "";
    private Location loc;
    private boolean running = false;

    // compass
    private SensorManager sm;
    private Sensor rotSensor;
    private boolean compassOn = false;
    private float heading = 0f, declination = 0f, sinAvg = 0f, cosAvg = 1f;
    private boolean firstSample = true;
    private final float[] rotMat = new float[9], remapped = new float[9], orient = new float[3];
    private TextView compassBtn;
    private int tab = 0;

    private TextView statusTv, netsTv, infoLocTv, infoLookupTv;
    private LinearLayout listBox;
    private ScrollView towersPage, infoPage;
    private TowerView radar;
    private EditText keyEt;
    private final TextView[] tabs = new TextView[3];

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ callbacks
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            poll();
            handler.postDelayed(this, 4000);
        }
    };

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (loc == null || l.getAccuracy() <= loc.getAccuracy() + 50f || l.getTime() > loc.getTime() + 60000) loc = l;
            refresh();
        }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
        @Override public void onStatusChanged(String p, int s, Bundle e) { }
    };

    // ------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        prefs = getSharedPreferences("celltowers", MODE_PRIVATE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        compassOn = prefs.getBoolean("compass_on", false) && rotSensor != null;
        buildUi();
        if (!hasPerm()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    @Override protected void onResume() { super.onResume(); start(); }
    @Override protected void onPause() { super.onPause(); stop(); }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        start();
        refresh();
    }

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (running || !hasPerm()) { refresh(); return; }
        running = true;
        try {
            Location a = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location b = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (a != null && (b == null || a.getTime() >= b.getTime())) loc = a; else if (b != null) loc = b;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, locListener);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, locListener);
        } catch (SecurityException | IllegalArgumentException e) { /* handled by refresh() */ }
        handler.post(poller);
        registerCompass();
    }

    private void stop() {
        handler.removeCallbacks(poller);
        try { lm.removeUpdates(locListener); } catch (Exception e) { }
        sm.unregisterListener(this);
        running = false;
    }

    // ------------------------------------------------------------- reading the towers
    private void poll() {
        if (!hasPerm()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) requestFresh();
            else handleCells(tm.getAllCellInfo());
        } catch (SecurityException e) {
            handleCells(null);
        }
    }

    @TargetApi(29)
    private void requestFresh() {
        tm.requestCellInfoUpdate(getMainExecutor(), new TelephonyManager.CellInfoCallback() {
            @Override public void onCellInfo(List<CellInfo> infos) { handleCells(infos); }
            @Override public void onError(int errorCode, Throwable detail) {
                try { handleCells(tm.getAllCellInfo()); } catch (SecurityException e) { handleCells(null); }
            }
        });
    }

    private static String s(String v) { return v == null ? "" : v; }
    private static long n(int v) { return v == Integer.MAX_VALUE ? -1 : v; }
    private static long n(long v) { return v == Long.MAX_VALUE ? -1 : v; }

    private void handleCells(List<CellInfo> infos) {
        List<Tower> out = new ArrayList<>();
        if (infos != null) {
            for (CellInfo ci : infos) {
                Tower t = new Tower();
                t.registered = ci.isRegistered();
                try {
                    if (ci instanceof CellInfoLte) {
                        CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                        t.tech = "4G";
                        t.mcc = Build.VERSION.SDK_INT >= 28 ? s(id.getMccString()) : (n(id.getMcc()) < 0 ? "" : String.valueOf(id.getMcc()));
                        t.mnc = Build.VERSION.SDK_INT >= 28 ? s(id.getMncString()) : (n(id.getMnc()) < 0 ? "" : String.valueOf(id.getMnc()));
                        t.area = n(id.getTac()); t.cid = n(id.getCi()); t.pci = (int) n(id.getPci()); t.chan = (int) n(id.getEarfcn());
                        t.dbm = ((CellInfoLte) ci).getCellSignalStrength().getDbm();
                    } else if (ci instanceof CellInfoGsm) {
                        CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
                        t.tech = "2G";
                        t.mcc = Build.VERSION.SDK_INT >= 28 ? s(id.getMccString()) : (n(id.getMcc()) < 0 ? "" : String.valueOf(id.getMcc()));
                        t.mnc = Build.VERSION.SDK_INT >= 28 ? s(id.getMncString()) : (n(id.getMnc()) < 0 ? "" : String.valueOf(id.getMnc()));
                        t.area = n(id.getLac()); t.cid = n(id.getCid()); t.chan = (int) n(id.getArfcn());
                        t.dbm = ((CellInfoGsm) ci).getCellSignalStrength().getDbm();
                    } else if (ci instanceof CellInfoWcdma) {
                        CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
                        t.tech = "3G";
                        t.mcc = Build.VERSION.SDK_INT >= 28 ? s(id.getMccString()) : (n(id.getMcc()) < 0 ? "" : String.valueOf(id.getMcc()));
                        t.mnc = Build.VERSION.SDK_INT >= 28 ? s(id.getMncString()) : (n(id.getMnc()) < 0 ? "" : String.valueOf(id.getMnc()));
                        t.area = n(id.getLac()); t.cid = n(id.getCid()); t.pci = (int) n(id.getPsc()); t.chan = (int) n(id.getUarfcn());
                        t.dbm = ((CellInfoWcdma) ci).getCellSignalStrength().getDbm();
                    } else if (ci instanceof CellInfoCdma) {
                        CellIdentityCdma id = ((CellInfoCdma) ci).getCellIdentity();
                        t.tech = "CDMA";
                        t.area = n(id.getNetworkId()); t.cid = n(id.getBasestationId());
                        t.dbm = ((CellInfoCdma) ci).getCellSignalStrength().getDbm();
                        int la = id.getLatitude(), lo = id.getLongitude();
                        if (la != Integer.MAX_VALUE && lo != Integer.MAX_VALUE && (la != 0 || lo != 0)) {
                            t.lat = la / 14400.0; t.lon = lo / 14400.0;   // quarter-seconds -> degrees
                        }
                    } else if (!(Build.VERSION.SDK_INT >= 29 && parseNewer(ci, t))) {
                        continue;
                    }
                } catch (Exception e) { continue; }
                out.add(t);
            }
        }
        towers = out;
        applyLookups();
        refresh();
    }

    /** 5G (NR) and TD-SCDMA exist only from Android 10. */
    @TargetApi(29)
    private boolean parseNewer(CellInfo ci, Tower t) {
        if (ci instanceof android.telephony.CellInfoNr) {
            android.telephony.CellInfoNr nr = (android.telephony.CellInfoNr) ci;
            android.telephony.CellIdentityNr id = (android.telephony.CellIdentityNr) nr.getCellIdentity();
            t.tech = "5G";
            t.mcc = s(id.getMccString()); t.mnc = s(id.getMncString());
            t.area = n(id.getTac()); t.cid = n(id.getNci()); t.pci = (int) n(id.getPci()); t.chan = (int) n(id.getNrarfcn());
            t.dbm = nr.getCellSignalStrength().getDbm();
            return true;
        }
        if (ci instanceof android.telephony.CellInfoTdscdma) {
            android.telephony.CellInfoTdscdma td = (android.telephony.CellInfoTdscdma) ci;
            android.telephony.CellIdentityTdscdma id = td.getCellIdentity();
            t.tech = "3G";
            t.mcc = s(id.getMccString()); t.mnc = s(id.getMncString());
            t.area = n(id.getLac()); t.cid = n(id.getCid()); t.pci = (int) n(id.getCpid()); t.chan = (int) n(id.getUarfcn());
            t.dbm = td.getCellSignalStrength().getDbm();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------- tower position lookup (OpenCelliD)
    private void applyLookups() {
        final String apiKey = prefs.getString("oc_key", "").trim();
        String phoneOp = s(tm.getNetworkOperator());
        for (Tower t : towers) {
            if (t.mcc.length() > 0 && t.mnc.length() > 0 && t.registered && phoneOp.equals(t.mcc + t.mnc) && s(tm.getNetworkOperatorName()).length() > 0)
                t.operator = tm.getNetworkOperatorName();
            else
                t.operator = Operators.name(t.mcc, t.mnc);
            if (t.hasPos() || !t.lookupable()) continue;
            final String k = t.key();
            double[] c = found.get(k);
            if (c == null) {
                String saved = prefs.getString("c_" + k, null);
                if (saved != null) { c = parseCache(saved); found.put(k, c); }
            }
            if (c != null) {
                if (!Double.isNaN(c[0])) { t.lat = c[0]; t.lon = c[1]; t.rangeM = (int) c[2]; }
                continue;
            }
            if (apiKey.length() == 0 || pending.contains(k)) continue;
            pending.add(k);
            final String mcc = t.mcc, mnc = t.mnc;
            final long area = t.area, cid = t.cid;
            io.execute(new Runnable() { @Override public void run() { lookup(k, apiKey, mcc, mnc, area, cid); } });
        }
    }

    private static double[] parseCache(String sv) {
        if (sv.equals("NF")) return new double[]{Double.NaN, Double.NaN, -1};
        String[] p = sv.split(",");
        try { return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])}; }
        catch (Exception e) { return new double[]{Double.NaN, Double.NaN, -1}; }
    }

    private static String num(String v) {
        try { return String.valueOf(Integer.parseInt(v)); } catch (Exception e) { return v; }
    }

    private void lookup(String k, String apiKey, String mcc, String mnc, long area, long cid) {
        double[] res = null;
        try {
            URL u = new URL("https://opencellid.org/cell/get?key=" + URLEncoder.encode(apiKey, "UTF-8")
                    + "&mcc=" + num(mcc) + "&mnc=" + num(mnc) + "&lac=" + area + "&cellid=" + cid + "&format=json");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            JSONObject j = new JSONObject(sb.toString());
            if (j.has("lat") && j.has("lon")) {
                res = new double[]{j.getDouble("lat"), j.getDouble("lon"), j.optDouble("range", -1)};
                lookupStatus = "OK";
            } else {
                String err = j.optString("error", "Unknown error");
                if (err.toLowerCase().contains("not found")) { res = new double[]{Double.NaN, Double.NaN, -1}; lookupStatus = "Some towers are not in the database"; }
                else lookupStatus = err;
            }
        } catch (Exception e) {
            lookupStatus = "No internet / server not reachable";
        }
        pending.remove(k);
        if (res != null) {
            found.put(k, res);
            prefs.edit().putString("c_" + k, Double.isNaN(res[0]) ? "NF" : res[0] + "," + res[1] + "," + res[2]).apply();
        }
        handler.post(new Runnable() { @Override public void run() { applyLookups(); refresh(); } });
    }

    // ------------------------------------------------------------- UI
    private GradientDrawable box(int fill, int edge, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(1), edge);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView tv(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(10), dp(28), dp(10), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(box(CARD, EDGE, 12));
        head.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusTv = tv("", 15, Color.WHITE);
        statusTv.setTypeface(Typeface.DEFAULT_BOLD);
        netsTv = tv("", 13, GRAY);
        netsTv.setPadding(0, dp(4), 0, 0);
        head.addView(statusTv);
        head.addView(netsTv);
        root.addView(head);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, dp(8), 0, dp(8));
        String[] names = {"Towers", "Radar", "Info"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = tv(names[i], 15, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { tab = idx; showTab(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabRow.addView(t, lp);
            tabs[i] = t;
        }
        root.addView(tabRow);

        FrameLayout frame = new FrameLayout(this);

        towersPage = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        towersPage.addView(listBox);
        frame.addView(towersPage);

        radar = new TowerView(this);
        frame.addView(radar);

        compassBtn = tv("", 14, Color.WHITE);
        compassBtn.setGravity(Gravity.CENTER);
        compassBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        compassBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleCompass(); } });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(4), dp(4), 0);
        frame.addView(compassBtn, cl);
        updateCompassBtn();

        infoPage = new ScrollView(this);
        infoPage.addView(buildInfo());
        frame.addView(infoPage);

        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView credit = tv("By: Black Falcon \uD83E\uDD85", 12, GRAY);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(6), 0, 0);
        root.addView(credit);

        setContentView(root);
        showTab();
    }

    private View buildInfo() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(12));

        box.addView(tv("Your position", 16, AMBER));
        infoLocTv = tv("", 14, Color.WHITE);
        infoLocTv.setPadding(0, dp(2), 0, dp(14));
        box.addView(infoLocTv);

        box.addView(tv("Tower positions (OpenCelliD key)", 16, AMBER));
        TextView why = tv("A phone only knows a tower's ID, not where it stands. Positions come from the free OpenCelliD database: "
                + "make a free account on opencellid.org, copy your API key and paste it here. Results are saved on the phone.", 13, GRAY);
        why.setPadding(0, dp(2), 0, dp(6));
        box.addView(why);

        keyEt = new EditText(this);
        keyEt.setTextColor(Color.WHITE);
        keyEt.setHintTextColor(GRAY);
        keyEt.setHint("OpenCelliD API key");
        keyEt.setSingleLine(true);
        keyEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyEt.setText(prefs.getString("oc_key", ""));
        box.addView(keyEt);

        TextView save = button("Save key");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString("oc_key", keyEt.getText().toString().trim()).apply();
                lookupStatus = "";
                Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                applyLookups();
                refresh();
            }
        });
        box.addView(save);

        infoLookupTv = tv("", 13, GRAY);
        infoLookupTv.setPadding(0, dp(6), 0, dp(14));
        box.addView(infoLookupTv);

        box.addView(tv("All operators in the area", 16, AMBER));
        TextView note = tv("The phone's modem normally lists only the serving tower and a few neighbours of your own SIM's network. "
                + "To see every operator that has coverage here, use the system search:", 13, GRAY);
        note.setPadding(0, dp(2), 0, dp(6));
        box.addView(note);
        TextView scan = button("Search networks (Android settings)");
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception e2) { }
                }
            }
        });
        box.addView(scan);

        TextView credit = tv("Brought to you By: Black Falcon \uD83E\uDD85", 13, GRAY);
        credit.setPadding(0, dp(18), 0, 0);
        box.addView(credit);
        return box;
    }

    private TextView button(String text) {
        TextView b = tv(text, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(box(0xFF1C1C3A, BLUE, 10));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void showTab() {
        towersPage.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        radar.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        compassBtn.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        infoPage.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            tabs[i].setBackground(box(i == tab ? 0xFF1C1C3A : CARD, i == tab ? BLUE : EDGE, 10));
            tabs[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private static String dir8(float b) {
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[Math.round(((b % 360) + 360) % 360 / 45f) % 8];
    }

    private static String distText(float m) {
        return m >= 1000 ? String.format("%.1f km", m / 1000f) : String.format("%d m", Math.round(m));
    }

    // ------------------------------------------------------------- compass on / off
    private void updateCompassBtn() {
        compassBtn.setText("\uD83E\uDDED  " + (compassOn ? "ON" : "OFF"));
        compassBtn.setTextColor(compassOn ? GREEN : GRAY);
        compassBtn.setBackground(box(0xFF0C0C1E, compassOn ? GREEN : EDGE, 10));
    }

    private void toggleCompass() {
        if (rotSensor == null) {
            Toast.makeText(this, "This phone has no compass sensor", Toast.LENGTH_SHORT).show();
            return;
        }
        compassOn = !compassOn;
        prefs.edit().putBoolean("compass_on", compassOn).apply();
        if (compassOn) registerCompass(); else sm.unregisterListener(this);
        updateCompassBtn();
        radar.setHeading(heading, compassOn);
    }

    private void registerCompass() {
        firstSample = true;
        if (compassOn && rotSensor != null) sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI);
        radar.setHeading(heading, compassOn);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!compassOn || e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rotMat, e.values);
        float[] m = rotMat;
        // phone held upright (screen facing you) -> remap so the top edge is the pointing direction
        if (Math.abs(rotMat[8]) < 0.7f) {
            SensorManager.remapCoordinateSystem(rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
            m = remapped;
        }
        SensorManager.getOrientation(m, orient);
        float az = (float) Math.toDegrees(orient[0]) + declination;
        double r = Math.toRadians(az);
        if (firstSample) { sinAvg = (float) Math.sin(r); cosAvg = (float) Math.cos(r); firstSample = false; }
        else { sinAvg += 0.15f * ((float) Math.sin(r) - sinAvg); cosAvg += 0.15f * ((float) Math.cos(r) - cosAvg); }
        heading = (float) ((Math.toDegrees(Math.atan2(sinAvg, cosAvg)) + 360) % 360);
        radar.setHeading(heading, true);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void refresh() {
        if (loc != null) {
            declination = new GeomagneticField((float) loc.getLatitude(), (float) loc.getLongitude(), (float) loc.getAltitude(), System.currentTimeMillis()).getDeclination();
        }
        // distance + direction from me to each tower
        for (Tower t : towers) {
            if (loc != null && t.hasPos()) {
                float[] r = new float[2];
                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), t.lat, t.lon, r);
                t.distM = r[0];
                t.bearing = (r[1] + 360f) % 360f;
            } else t.distM = -1f;
        }
        Collections.sort(towers, new Comparator<Tower>() {
            @Override public int compare(Tower a, Tower b) {
                if (a.registered != b.registered) return a.registered ? -1 : 1;
                return Integer.compare(b.hasDbm() ? b.dbm : -999, a.hasDbm() ? a.dbm : -999);
            }
        });

        // ---- header
        if (!hasPerm()) {
            statusTv.setText("Location permission needed");
            statusTv.setTextColor(RED);
            netsTv.setText("Android gives cell-tower info only to apps with the Location permission.");
        } else if (towers.isEmpty()) {
            statusTv.setText("No towers reported");
            statusTv.setTextColor(AMBER);
            netsTv.setText("Check that a SIM is inserted, Airplane mode is off and Location is ON.");
        } else {
            Tower conn = null;
            for (Tower t : towers) if (t.registered) { conn = t; break; }
            if (conn != null) {
                statusTv.setText("Connected: " + conn.operator + " " + conn.tech + (conn.hasDbm() ? "  " + conn.dbm + " dBm" : ""));
                statusTv.setTextColor(GREEN);
            } else {
                statusTv.setText("Not connected to a tower");
                statusTv.setTextColor(AMBER);
            }
            Map<String, Integer> cnt = new LinkedHashMap<>();
            for (Tower t : towers) { Integer c = cnt.get(t.operator); cnt.put(t.operator, c == null ? 1 : c + 1); }
            StringBuilder sb = new StringBuilder("Networks seen: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : cnt.entrySet()) {
                if (!first) sb.append("  \u2022  ");
                sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
                first = false;
            }
            netsTv.setText(sb.toString());
        }

        // ---- list
        listBox.removeAllViews();
        for (Tower t : towers) listBox.addView(towerCard(t));
        radar.setData(towers);

        // ---- info
        if (loc != null)
            infoLocTv.setText(String.format("%.6f, %.6f  (\u00B1%d m)", loc.getLatitude(), loc.getLongitude(), Math.round(loc.getAccuracy())));
        else infoLocTv.setText("Waiting for location\u2026");
        boolean hasKey = prefs.getString("oc_key", "").trim().length() > 0;
        String st = !hasKey ? "No key saved \u2014 tower positions are not looked up."
                : (pending.isEmpty() ? "" : "Looking up " + pending.size() + " tower(s)\u2026 ");
        if (lookupStatus.length() > 0) st += (st.length() > 0 ? " " : "") + "Last result: " + lookupStatus;
        infoLookupTv.setText(st);
    }

    private View towerCard(final Tower t) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(CARD, t.registered ? GREEN : EDGE, 12));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);

        TextView title = tv((t.registered ? "\u2605 CONNECTED  " : "") + t.operator + "  \u2022  " + t.tech, 16, t.registered ? GREEN : Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(title);

        String ids = "Cell ID " + (t.cid >= 0 ? t.cid : "?") + "  \u2022  " + (t.tech.equals("2G") || t.tech.equals("3G") ? "LAC " : "TAC ") + (t.area >= 0 ? t.area : "?");
        if (t.pci >= 0) ids += "  \u2022  " + (t.tech.equals("3G") ? "PSC " : "PCI ") + t.pci;
        c.addView(tv(ids, 13, Color.WHITE));

        String line = (t.mcc.length() > 0 ? "MCC-MNC " + t.mcc + "-" + t.mnc : "") + (t.chan >= 0 ? "  \u2022  Channel " + t.chan : "");
        if (line.length() > 0) c.addView(tv(line, 12, GRAY));

        if (t.hasDbm()) {
            int q = t.dbm >= -85 ? GREEN : (t.dbm >= -105 ? AMBER : RED);
            String lvl = t.dbm >= -85 ? "strong" : (t.dbm >= -105 ? "fair" : "weak");
            c.addView(tv("Signal " + t.dbm + " dBm (" + lvl + ")", 13, q));
        }

        String where;
        int wc = GRAY;
        if (t.hasPos()) {
            where = String.format("Tower at %.5f, %.5f", t.lat, t.lon);
            if (t.distM >= 0) where += "\n" + distText(t.distM) + " away, direction " + dir8(t.bearing) + " (" + Math.round(t.bearing) + "\u00B0)";
            if (t.rangeM > 0) where += "  \u2022  coverage ~" + distText(t.rangeM);
            where += "\nTap to open in Maps";
            wc = BLUE;
        } else if (!t.lookupable()) {
            where = t.tech.equals("5G") ? "5G tower positions are not in the free database" : "Tower position not available";
        } else if (found.containsKey(t.key())) {
            where = "This tower is not in the OpenCelliD database";
        } else if (prefs.getString("oc_key", "").trim().length() == 0) {
            where = "Position unknown \u2014 add a free OpenCelliD key in the Info tab";
        } else {
            where = "Looking up position\u2026";
        }
        TextView w = tv(where, 13, wc);
        w.setPadding(0, dp(4), 0, 0);
        c.addView(w);

        if (t.hasPos()) {
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + t.lat + "," + t.lon + "?q=" + t.lat + "," + t.lon + "(" + Uri.encode(t.operator + " tower") + ")")));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No maps app found", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        return c;
    }
}
