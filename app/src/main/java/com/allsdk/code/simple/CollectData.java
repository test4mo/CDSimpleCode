package com.allsdk.code.simple;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApkChecksum;
import android.content.pm.ApplicationInfo;
import android.content.pm.Checksum;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

/**
 * 数据采集代码
 * 依赖需求：
 * implementation 'com.android.installreferrer:installreferrer:2.2'
 * implementation "com.google.android.gms:play-services-ads-identifier:18.1.0"
 * <p>
 * 权限需求：
 * <p>
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
 * <uses-permission android:name="com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE" />
 * <queries>
 * <package android:name="com.android.vending" />
 * <package android:name="com.facebook.katana" />
 * </queries>
 */
public final class CollectData implements Runnable {
    private final static String TAG = "CollectData";

    private final static CollectData dc = new CollectData();

    public static CollectData getInstance() {
        return dc;
    }

    //
    // 采集数据的代码
    //

    private final String API_URL = "https://i.whathattemp.com/api/cd/testtoken";   // 根据商务合作 分配API地址, 换掉 "testtoken"
    private Context mContext;
    /**
     * client device id
     */
    private String cdid;
    SharedPreferences sp;
    /**
     * APK is debug
     */
    boolean dbg = false;

    boolean listenTouch = false;

    /**
     * init logic
     */
    public CollectData init(Context ctx) {
        if (mContext == null) {
            mContext = ctx;
            sp = mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
            getVerCode();
            listenTouch();
        }
        return this;
    }

    private void listenTouch() {
        String spKey = "motionEvent";
        if (listenTouch || sp.contains(spKey)) {
            listenTouch = true;
            return;
        }
        if (mContext instanceof Activity) {
            Activity activity = ((Activity) mContext);
            activity.getWindow().getDecorView().setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (listenTouch) {
                        return false;
                    }
                    listenTouch = true;
                    sp.edit().putString(spKey, dumpMotionEvent(motionEvent)).commit();
                    return false;
                }
            });
        }
    }

    /**
     * 开始
     */
    public void start() {
        new Thread(this).start();
    }

    /**
     * 如果有历史的 referrer 可以用, 可以传入
     *
     * @param referrer
     * @param install_version
     * @param referrer_click_timestamp_seconds
     * @param referrer_click_timestamp_server_seconds
     * @param install_begin_timestamp_seconds
     * @param install_begin_timestamp_server_seconds
     * @param google_play_instant
     */
    public void setInstallReferrer(String referrer,
                                   String install_version,
                                   long referrer_click_timestamp_seconds,
                                   long referrer_click_timestamp_server_seconds,
                                   long install_begin_timestamp_seconds,
                                   long install_begin_timestamp_server_seconds,
                                   boolean google_play_instant) {
        if (!TextUtils.isEmpty(referrer)) {
            JSONObject json = new JSONObject();
            try {
                json.put("referrer", referrer);
                json.put("install_version", install_version);
                json.put("referrer_click_timestamp_seconds", referrer_click_timestamp_seconds);
                json.put("referrer_click_timestamp_server_seconds", referrer_click_timestamp_server_seconds);
                json.put("install_begin_timestamp_server_seconds", install_begin_timestamp_server_seconds);
                json.put("install_begin_timestamp_seconds", install_begin_timestamp_seconds);
                json.put("google_play_instant", google_play_instant);
                sp.edit().putString("referrer", json.toString()).commit();
            } catch (Exception e) {
                showError(e);
            }
        }
    }

    private synchronized void readOrCreateID() {
        if (TextUtils.isEmpty(cdid)) {
            cdid = sp.getString("CDID", null);
            if (TextUtils.isEmpty(cdid)) {
                cdid = UUID.randomUUID().toString().replace("-", "");
                sp.edit().putString("CDID", cdid).commit();
            }
        }
    }


    private JSONObject getInstallReferrer() throws Exception {
        String old = sp.getString("referrer", null);
        if (!TextUtils.isEmpty(old)) {
            try {
                return new JSONObject(old);
            } catch (Exception e) {
                showError(e);
            }
        }


        CountDownLatch cd = new CountDownLatch(1);
        InstallReferrerClient client = InstallReferrerClient.newBuilder(mContext).build();
        client.startConnection(new InstallReferrerStateListener() {

            @Override
            public void onInstallReferrerSetupFinished(int responseCode) {
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    try {
                        ReferrerDetails details = client.getInstallReferrer();
                        setInstallReferrer(details.getInstallReferrer(),
                                details.getInstallVersion(),
                                details.getReferrerClickTimestampSeconds(),
                                details.getReferrerClickTimestampServerSeconds(),
                                details.getInstallBeginTimestampSeconds(),
                                details.getInstallBeginTimestampServerSeconds(),
                                details.getGooglePlayInstantParam()
                        );
                    } catch (Exception e) {
                        showError(e);
                    }
                }
                cd.countDown();
            }

            @Override
            public void onInstallReferrerServiceDisconnected() {
                cd.countDown();
            }
        });

        try {
            cd.await(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            showError(e);
        }

        old = sp.getString("referrer", null);
        if (!TextUtils.isEmpty(old)) {
            try {
                return new JSONObject(old);
            } catch (Exception e) {
                showError(e);
            }
        }
        return null;
    }

    private JSONObject getFBData() throws Exception {
        Uri uri = Uri.parse("content://com.facebook.katana.provider.AttributionIdProvider");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, new String[]{"limit_tracking", "androidid", "aid"}, null, null, null);
        if (cursor == null || !cursor.moveToFirst()) {
            return null;
        } else {
            int i1 = cursor.getColumnIndex("androidid");
            int i2 = cursor.getColumnIndex("aid");
            int i3 = cursor.getColumnIndex("limit_tracking");
            JSONObject json = new JSONObject();
            json.put("aid", cursor.getString(i2));
            json.put("androidid", cursor.getString(i1));
            json.put("limit_tracking", cursor.getString(i3));
            return json;
        }
    }


    private static void CLOSE(Closeable... cc) {
        if (cc == null || cc.length < 1) {
            return;
        }
        for (Closeable c : cc) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void run() {
        readOrCreateID();
        String cdToken = getSubmitToken();
        showLog(String.format("%s - %s", cdid, cdToken));
        if (cdToken == null) {
            return;
        }
        OutputStream out = null;
        try {
            JSONObject json = new JSONObject();
            json.put("cdid", cdid);
            json.put("pkg", mContext.getPackageName());
            json.put("avc", getVerCode());
            json.put("avn", getVerName());
            json.put("cs", Build.MANUFACTURER);
            json.put("jx", Build.MODEL);
            json.put("fp", Build.FINGERPRINT);
            json.put("user", Build.USER);
            json.put("vc", Build.VERSION.SDK_INT);
            json.put("cn", getCountry());    // country
            json.put("lng", getLanguage());    // language
            json.put("extras", getExtras());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                json.put("sku", Build.SKU);
                json.put("scs", Build.SOC_MANUFACTURER);
                json.put("smd", Build.SOC_MODEL);
            }
            try {
                String gaid = AdvertisingIdClient.getAdvertisingIdInfo(mContext).getId();
                json.put("gaid", gaid);
            } catch (Exception e) {
                showError(e); // This exception can be ignored during development
            }
            try {
                JSONObject fbdata = getFBData();
                if (fbdata != null && fbdata.length() > 0) {
                    json.put("fbdata", fbdata);
                }
            } catch (Exception e) {
                showError(e);  // This exception can be ignored during development
            }
            try {
                JSONObject referrer = getInstallReferrer();
                if (referrer != null && referrer.length() > 0) {
                    json.put("referrer", referrer);
                }
            } catch (Exception e) {
                showError(e);  // This exception can be ignored during development
            }
            try {
                JSONObject apkhash = !dbg && cdToken.contains("hash") ? getApkHash() : null;
                if (apkhash != null && apkhash.length() > 0) {
                    json.put("hash", apkhash);
                }
            } catch (Exception e) {
                showError(e); // This exception can be ignored during development
            }
            json.put("prop", getProp());
            json.put("tz", getTimeZone());
            json.put("build", getBUILD());
            showLog(json.toString());
            String apiUrl = String.format("%s?cdt=%s", API_URL, cdToken);
            byte[] outBytes = compress(json.toString().getBytes(StandardCharsets.UTF_8));

            URL url = new URL(apiUrl);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestProperty("Content-Type", "application/binary");
            conn.setRequestProperty("Content-Length", "" + outBytes.length);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.connect();
            out = conn.getOutputStream();
            out.write(compress(json.toString().getBytes(StandardCharsets.UTF_8)));
            int code = conn.getResponseCode();
            if (code != HttpsURLConnection.HTTP_OK) {
                throw new Exception("http error: " + code);
            }
            showLog("submit.code: " + code);
        } catch (Exception e) {
            showError(e);
            new Handler(mContext.getMainLooper()).postDelayed(() -> start(), 3600_000 + new Random().nextInt(1000_000));
        } finally {
            CLOSE(out);
        }
    }

    private JSONObject getExtras() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("sdg", getStorageSize());
            jsonObject.put("spc", getFileSpace());
            jsonObject.put("mem", getMemorySize());
            jsonObject.put("rmem", Runtime.getRuntime().maxMemory());
            jsonObject.put("scr", getScreen());
            if (sp.contains("motionEvent")) {
                jsonObject.put("mevent", sp.getString("motionEvent", null));
            }
            jsonObject.put("pkg", mContext.getPackageName());
            if (mContext instanceof Activity) {
                jsonObject.put("clzz", mContext.getClass().getName());
            }

            jsonObject.put("http.agent", System.getProperty("http.agent", ""));

            CountDownLatch countDownLatch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    WebView v = new WebView(mContext);
                    jsonObject.put("webview.agent", v.getSettings().getUserAgentString());
                } catch (Exception e) {
                    showError(e);
                }
                countDownLatch.countDown();
            });
            countDownLatch.await();
        } catch (Exception e) {
            showError(e);
        }
        return jsonObject;
    }

    private JSONObject getScreen() {
        try {
            JSONObject jsonObject = new JSONObject();
            if (mContext instanceof Activity) {
                Activity activity = (Activity) mContext;
                DisplayMetrics dm = new DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
                jsonObject.put("w", dm.widthPixels);
                jsonObject.put("h", dm.heightPixels);
                jsonObject.put("d", dm.densityDpi);
                jsonObject.put("f", String.valueOf(dm.density));
            }
            return jsonObject;
        } catch (Exception e) {
            showError(e);
        }
        return null;
    }

    private String getCountry() {
        String cn1 = Locale.getDefault().getCountry() + "," + mContext.getResources().getConfiguration().locale.getCountry();
        try {
            TelephonyManager telManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
            cn1 += "," + telManager.getSimCountryIso();
        } catch (Exception e) {
            showError(e);
        }
        return cn1;
    }


    private String getLanguage() {
        String cn1 = Locale.getDefault().getLanguage() + "," + mContext.getResources().getConfiguration().locale.getLanguage();
        return cn1;
    }


    JSONObject getBUILD() {
        JSONObject jsonObject = new JSONObject();
        for (Field field : Build.class.getDeclaredFields()) {
            field.setAccessible(true);
            try {
                jsonObject.put(field.getName(), field.get(null));
            } catch (Exception e) {
                showError(e);
            }
        }
        return jsonObject;
    }

    JSONObject getTimeZone() {
        TimeZone tz = TimeZone.getDefault();
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("s", tz.getDisplayName(false, TimeZone.SHORT));
            jsonObject.put("l", tz.getDisplayName(false, TimeZone.LONG));
            jsonObject.put("o", tz.getOffset(System.currentTimeMillis()));
            jsonObject.put("i", tz.getID());
        } catch (Exception e) {
            showError(e);
        }
        return jsonObject;
    }

    private String getProp() {
        StringBuilder sb = new StringBuilder();

        String line;
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec("getprop");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 1024);
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            showError(e);  // This exception can be ignored during development
        } finally {
            CLOSE(reader);
        }
        return sb.toString();
    }

    private String getSubmitToken() {
        InputStream in = null;
        try {
            URL url = new URL(API_URL + "/init?cdid=" + cdid + "&t=" + System.currentTimeMillis() + "&pkg=" + mContext.getPackageName() + "&avc=" + getVerCode());
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == HttpsURLConnection.HTTP_OK) {
                in = conn.getInputStream();
                byte[] bytes = new byte[1024 * 4];
                int len = in.read(bytes);
                if (len > 0) {
                    return new String(bytes, 0, len);
                }
            } else if (code == 404) {
                showLog("http 404");     // This exception can be ignored during development
            } else {
                throw new Exception("http code: " + code);
            }
        } catch (Exception e) {
            showError(e);
            new Handler(mContext.getMainLooper()).postDelayed(() -> start(), 3600_000 + new Random().nextInt(1000_000));
        } finally {
            CLOSE(in);
        }
        return null;
    }

    // Google play App offical public key, don't modify
    public static final String SIGN_GP =
            "308204433082032ba003020102020900c2e08746644a308d300d06092a864886f70d01010405003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3038303832313233313333345a170d3336303130373233313333345a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820120300d06092a864886f70d01010105000382010d00308201080282010100ab562e00d83ba208ae0a966f124e29da11f2ab56d08f58e2cca91303e9b754d372f640a71b1dcb130967624e4656a7776a92193db2e5bfb724a91e77188b0e6a47a43b33d9609b77183145ccdf7b2e586674c9e1565b1f4c6a5955bff251a63dabf9c55c27222252e875e4f8154a645f897168c0b1bfc612eabf785769bb34aa7984dc7e2ea2764cae8307d8c17154d7ee5f64a51a44a602c249054157dc02cd5f5c0e55fbef8519fbe327f0b1511692c5a06f19d18385f5c4dbc2d6b93f68cc2979c70e18ab93866b3bd5db8999552a0e3b4c99df58fb918bedc182ba35e003c1b4b10dd244a8ee24fffd333872ab5221985edab0fc0d0b145b6aa192858e79020103a381d93081d6301d0603551d0e04160414c77d8cc2211756259a7fd382df6be398e4d786a53081a60603551d2304819e30819b8014c77d8cc2211756259a7fd382df6be398e4d786a5a178a4763074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964820900c2e08746644a308d300c0603551d13040530030101ff300d06092a864886f70d010104050003820101006dd252ceef85302c360aaace939bcff2cca904bb5d7a1661f8ae46b2994204d0ff4a68c7ed1a531ec4595a623ce60763b167297a7ae35712c407f208f0cb109429124d7b106219c084ca3eb3f9ad5fb871ef92269a8be28bf16d44c8d9a08e6cb2f005bb3fe2cb96447e868e731076ad45b33f6009ea19c161e62641aa99271dfd5228c5c587875ddb7f452758d661f6cc0cccb7352e424cc4365c523532f7325137593c4ae341f4db41edda0d0b1071a7c440f0fe9ea01cb627ca674369d084bd2fd911ff06cdbf2cfa10dc0f893ae35762919048c7efc64c7144178342f70581c9de573af55b390dd7fdb9418631895d5f759f30112687ff621410c069308a";

    /**
     * Verify whether the APP is installed through GP
     *
     * @return
     */
    private JSONObject getApkHash() {
        String spkey = "apk_hash_map_v" + getVerCode();
        if (sp.contains(spkey)) {
            String json = sp.getString(spkey, null);
            if (!TextUtils.isEmpty(json)) {
                try {
                    return new JSONObject(json);
                } catch (Exception e) {
                    showError(e);
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            JSONObject jsonObject = new JSONObject();
            CountDownLatch countDownLatch = new CountDownLatch(1);
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                PackageManager pm = mContext.getPackageManager();
                List<Certificate> certs = Arrays.asList(factory.generateCertificate(new ByteArrayInputStream(hexToBytes(SIGN_GP))));
                pm.requestChecksums(mContext.getPackageName(), false, Checksum.TYPE_WHOLE_MERKLE_ROOT_4K_SHA256 | Checksum.TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512 | Checksum.TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256, certs, new PackageManager.OnChecksumsReadyListener() {
                    @Override
                    public void onChecksumsReady(@NonNull List<ApkChecksum> list) {
                        try {
                            if (list != null && sp != null) {
                                boolean needCheckSign = true;
                                for (ApkChecksum acs : list) {
                                    try {
                                        if (needCheckSign) {
                                            needCheckSign = false;
                                            Certificate c = acs.getInstallerCertificate();
                                            if (c == null || !SIGN_GP.equalsIgnoreCase(bytesToHex(c.getEncoded()))) {
                                                jsonObject.put("sign", c == null ? "NULL" : "ERROR");
                                            }
                                        }
                                        jsonObject.put("" + acs.getType(), bytesToHex(acs.getValue()));
                                    } catch (Exception e) {
                                        showError(e);
                                    }
                                }
                            }
                        } finally {
                            countDownLatch.countDown();
                        }
                    }
                });

                countDownLatch.await(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                showError(e);
            }
            try {
                if (jsonObject.length() > 0) {
                    sp.edit().putString(spkey, jsonObject.toString()).commit();
                    return jsonObject;
                }
            } catch (Exception e) {
                showError(e);
            }
        }
        return null;
    }

    private static void showError(Throwable t) {
        if (getInstance().dbg) {
            Log.d(TAG, "" + t.getMessage(), t);
        }
    }

    /**
     * app version code
     */
    private long vc = 0;
    /**
     * app version name
     */
    private String avn = null;

    String getVerName() {
        if (vc > 0) {
            return avn;
        }
        getVerCode();
        return avn;
    }

    private long getVerCode() {
        if (vc > 0) {
            return vc;
        }
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(mContext.getPackageName(), PackageManager.GET_ACTIVITIES);
            avn = pi.versionName;
            this.dbg = (ApplicationInfo.FLAG_DEBUGGABLE & pi.applicationInfo.flags) != 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Exception e) {
            showError(e);
        }
        return 0;
    }

    /**
     * compress bytes
     *
     * @param inbuf
     * @return
     * @throws IOException
     */
    public static byte[] compress(byte[] inbuf) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream);
             InputStream inputStream = new ByteArrayInputStream(inbuf)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                gzipOutputStream.write(buffer, 0, len);
            }
        }
        return byteArrayOutputStream.toByteArray();
    }


    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String s) {
        if (s == null) {
            return null;
        }
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    void showLog(String msg) {
        if (!dbg) {
            return;
        }
        Log.d(TAG, msg);
    }

    long getStorageSize() {
        StatFs statFs = new StatFs(Environment.getDataDirectory().getPath());
        long blockSize = statFs.getBlockSizeLong();
        long totalBlocks = statFs.getBlockCountLong();
//        long availableBlocks = statFs.getAvailableBlocksLong();
        return totalBlocks * blockSize; // total space in bytes
    }

    long getFileSpace() {
        return Environment.getDataDirectory().getTotalSpace();
    }

    long getMemorySize() {
        try {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(memoryInfo);
            return memoryInfo.totalMem;
        } catch (Exception e) {
            showError(e);
        }
        return 0;
    }

    public static String dumpMotionEvent(MotionEvent event) {
        StringBuilder sb = new StringBuilder();

        InputDevice device = event.getDevice();
        List<InputDevice.MotionRange> ranges = device == null ? null : device.getMotionRanges();
        InputDevice.MotionRange range0 = device == null ? null : device.getMotionRange(MotionEvent.AXIS_X);
        InputDevice.MotionRange range1 = device == null ? null : device.getMotionRange(MotionEvent.AXIS_Y);

        sb.append("CLASS: ").append(event.getClass().getName()).append("\n")
                .append(", getDeviceId: ").append(event.getDeviceId()).append("\n")
                .append(", getDevice: ").append(device).append("\n")
                .append(", getPointerCount: ").append(event.getPointerCount()).append("\n")
                .append(", device.range.count: ").append(ranges == null ? "<NULL>" : ranges.size()).append("\n")
                .append(", device.range0: ").append(range0 == null ? "<NULL>" : range0.getRange()).append("\n")
                .append(", device.range1: ").append(range1 == null ? "<NULL>" : range1.getRange()).append("\n")
                .append(", getPressure: ").append(event.getPressure()).append("\n")
                .append(", getSize: ").append(event.getSize()).append("\n")
                .append(", getX: ").append(event.getX()).append("\n")
                .append(", getY: ").append(event.getY()).append("\n")
                .append(", getAction: ").append(event.getAction()).append("\n")
                .append(", getPressure: ").append(event.getPressure()).append("\n")
                .append(", getActionIndex: ").append(event.getActionIndex()).append("\n")
                .append(", getButtonState: ").append(event.getButtonState()).append("\n")
                .append(", getActionButton: ").append(event.getActionButton()).append("\n")
                .append(", getFlags: ").append(event.getFlags()).append("\n")
                .append(", getEdgeFlags: ").append(event.getEdgeFlags()).append("\n")
//                .append(", getClassification: ").append(event.getClassification()).append("\n")
                .append(", getActionMasked: ").append(event.getActionMasked()).append("\n")
                .append(", getHistorySize: ").append(event.getHistorySize()).append("\n")
                .append(", getSource: ").append(event.getSource()).append(" ?? ").append(InputDevice.SOURCE_TOUCHSCREEN | InputDevice.SOURCE_CLASS_POINTER).append("\n");

        for (int i = 0; i < event.getPointerCount(); i++) {
            MotionEvent.PointerProperties out = new MotionEvent.PointerProperties();
            event.getPointerProperties(i, out);
            sb.append(", getPointerProperties(").append(i).append(") = ")
                    .append("id: ").append(out.id)
                    .append(", toolType: ").append(out.toolType)
                    .append(", x:").append(event.getX()).append(" = ").append(event.getX(i))
                    .append(", y:").append(event.getY()).append(" = ").append(event.getY(i))
                    .append(", size:").append(event.getSize(i)).append("\n");

            MotionEvent.PointerCoords cp = new MotionEvent.PointerCoords();
            event.getPointerCoords(i, cp);
            sb.append(", getPointerCoords(").append(i).append(") = ")
                    .append("orientation: ").append(cp.orientation)
                    .append(", size: ").append(cp.size)
                    .append(", x: ").append(cp.x)
                    .append(", y: ").append(cp.y)
                    .append(", relativeX: ").append(cp.getAxisValue(MotionEvent.AXIS_RELATIVE_X))
                    .append(", relativeY: ").append(cp.getAxisValue(MotionEvent.AXIS_RELATIVE_Y))
                    .append(", pressure: ").append(cp.pressure)
                    .append(", toolMajor: ").append(cp.toolMajor)
                    .append(", toolMinor: ").append(cp.toolMinor)
                    .append(", touchMajor: ").append(cp.touchMajor)
                    .append(", touchMinor: ").append(cp.touchMinor)
                    .append("\n");

        }
        sb.append(", toString: ").append(event).append("\n");
        return sb.toString();
    }
}
