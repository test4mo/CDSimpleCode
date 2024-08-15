package com.allsdk.code.simple;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


/**
 * 数据采集代码
 * 依赖需求：
 *     implementation 'com.squareup.okhttp3:okhttp:3.12.0'
 *     implementation 'com.android.installreferrer:installreferrer:2.2'
 *     implementation "com.google.android.gms:play-services-ads-identifier:18.0.1"
 *
 * 权限需求：
 *
 *     <uses-permission android:name="android.permission.INTERNET" />
 *     <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
 *     <uses-permission android:name="com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE" />
 *     <queries>
 *         <package android:name="com.android.vending" />
 *         <package android:name="com.facebook.katana" />
 *     </queries>
 */
public final class CollectData implements Runnable {
    private final static String TAG = "CD";

    private final static CollectData dc = new CollectData();

    public static CollectData getInstance() {
        return dc;
    }

    //
    // 采集数据的代码
    //

    private final String API_URL = "https://i.allsdk.com/api/cd";
    private final static OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().build();

    private Context mContext;
    private String cdid;
    SharedPreferences sp;

    /**
     * 初始化
     */
    public CollectData init(Context ctx) {
        if (mContext == null) {
            mContext = ctx;
            sp = mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        }
        return this;
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
                Log.w(TAG, e.getMessage() + "", e);
            }
        }
    }

    private synchronized void readOrCreateID() {
        if (TextUtils.isEmpty(cdid)) {
            cdid = UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString("CDID", cdid).commit();
        }
    }


    private JSONObject getInstallReferrer() throws Exception {
        String old = sp.getString("referrer", null);
        if (!TextUtils.isEmpty(old)) {
            try {
                return new JSONObject(old);
            } catch (Exception e) {
                Log.w(TAG, e.getMessage() + "", e);
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
                        Log.w(TAG, e.getMessage() + "", e);
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
            Log.w(TAG, e.getMessage() + "", e);
        }

        old = sp.getString("referrer", null);
        if (!TextUtils.isEmpty(old)) {
            try {
                return new JSONObject(old);
            } catch (Exception e) {
                Log.w(TAG, e.getMessage() + "", e);
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
        if (!checkNeedSubmitData()) {
            return;
        }

        try {

            JSONObject json = new JSONObject();
            json.put("cs", Build.MANUFACTURER);
            json.put("jx", Build.MODEL);
            json.put("fp", Build.FINGERPRINT);
            json.put("user", Build.USER);
            json.put("vc", Build.VERSION.SDK_INT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                json.put("sku", Build.SKU);
                json.put("scs", Build.SOC_MANUFACTURER);
                json.put("smd", Build.SOC_MODEL);
            }
            String gaid = AdvertisingIdClient.getAdvertisingIdInfo(mContext).getId();
            json.put("gaid", gaid);
            JSONObject fbdata = getFBData();
            if (fbdata != null) {
                json.put("fbdata", fbdata);
            }
            JSONObject referrer = getInstallReferrer();
            if (referrer != null) {
                json.put("referrer", referrer);
            }

            // 发送数据
            RequestBody body = RequestBody.create(MediaType.parse("application/binary"), compress(json.toString().getBytes(StandardCharsets.UTF_8)));
            Request request = new Request.Builder().url(API_URL).post(body).build();
            Response response = HTTP_CLIENT.newCall(request).execute();
            Log.d(TAG, "response.code: " + response.code());
            CLOSE(response);
        } catch (Exception e) {
            Log.w(TAG, e.getMessage() + "", e);
            new Handler(mContext.getMainLooper()).postDelayed(() -> start(), 3600_000 + new Random().nextInt(1000_000));
        }
    }

    private boolean checkNeedSubmitData() {
        Response response = null;
        try {
            response = HTTP_CLIENT.newCall(new Request.Builder().url(API_URL + "/init?cdid=" + cdid + "&t=" + System.currentTimeMillis()).build()).execute();
            int code = response.code();
            if (code == 404) {
                return true;
            } else if (code == 200) {
                return false;
            }
            throw new Exception("http code: " + code);
        } catch (Exception e) {
            Log.w(TAG, e.getMessage() + "", e);
            new Handler(mContext.getMainLooper()).postDelayed(() -> start(), 3600_000 + new Random().nextInt(1000_000));
        } finally {
            CLOSE(response);
        }
        return true;
    }

    /**
     * 压缩
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
}
