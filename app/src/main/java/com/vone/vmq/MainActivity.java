package com.vone.vmq;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;


public class MainActivity extends AppCompatActivity {

    // VMQ APK 版本号
    private final static String VMQ_VERSION = "2.0.0";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView txthost;
    private TextView txtkey;
    private TextView txtAppId;

    private boolean isOk = false;
    private static final String TAG = "MainActivity";

    //检测监听的回调超时
    private static final long TEST_PUSH_TIMEOUT_MS = 4000;
    private static volatile boolean testPushPending = false;
    private final Runnable testPushTimeout = new Runnable() {
        @Override
        public void run() {
            if (testPushPending) {
                testPushPending = false;
                Toast.makeText(MainActivity.this, "4 秒内没收到监听回调：通知使用权可能已失效，或本应用通知被系统折叠/拦截", Toast.LENGTH_LONG).show();
            }
        }
    };

    private static String host;
    private static String key;
    private static String appId;
    int id = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txthost = (TextView) findViewById(R.id.txt_host);
        txtkey = (TextView) findViewById(R.id.txt_key);
        txtAppId = (TextView) findViewById(R.id.txt_app_id);

        //检测通知使用权是否启用
        if (!isNotificationListenersEnabled()) {
            Toast.makeText(MainActivity.this, "未开启通知使用权，正在跳转授权页", Toast.LENGTH_LONG).show();
            //跳转到通知使用权页面
            gotoNotificationAccessSetting();
        } else if (!Utils.checkBatteryWhiteList(this)) {
            Toast.makeText(MainActivity.this, "未加入电池优化白名单，正在跳转设置", Toast.LENGTH_LONG).show();
            Utils.gotoBatterySetting(this);
        }
        //重启监听服务
        if (!NeNotificationService2.isRunning) {
            toggleNotificationListenerService(this);
        }
        //读入保存的配置数据并显示
        SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
        host = read.getString("host", "");
        key = read.getString("key", "");
        appId = read.getString("app_id", "");

        if (host != null && key != null && !host.isEmpty() && !key.isEmpty()) {
            txthost.setText(" 通知地址：" + host);
            txtkey.setText(" 通讯密钥：" + key);
            txtAppId.setText(" 应用ID：" + appId);
            isOk = true;
        }
        Toast.makeText(MainActivity.this, "V免签开源免费免签系统 v" + VMQ_VERSION, Toast.LENGTH_SHORT).show();
    }

    //扫码配置
    public void startQrCode(View v) {
        // 申请相机权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        // 申请文件读写权限（部分朋友遇到相册选图需要读写权限的情况，这里一并写一下）
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // 申请权限
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, Constant.REQ_PERM_EXTERNAL_STORAGE);
            return;
        }
        // 二维码扫码
        Intent intent = new Intent(MainActivity.this, CaptureActivity.class);
        startActivityForResult(intent, Constant.REQ_QR_CODE);
    }

    //手动配置
    public void doInput(View v) {
        final EditText inputServer = new EditText(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请输入配置数据").setView(inputServer)
                .setNegativeButton("取消", null);
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                String scanResult = inputServer.getText().toString();
                // 使用正则表达式提取URL、sign和app_id
                // 兼容旧 QR(http://host/key)和 daojiAnime 新 QR(http://host/key/appId)
                Pattern pattern = Pattern.compile("^(https?://[^/]+)/([^/]+)(?:/([^/]+))?$");
                Matcher matcher = pattern.matcher(scanResult);

                if (!matcher.matches()) {
                    Toast.makeText(MainActivity.this, "数据错误，请您输入网站上显示的配置数据!", Toast.LENGTH_SHORT).show();
                    return;
                }

                String url = matcher.group(1);
                String signKey = matcher.group(2);
                String aid = matcher.group(3);  // 可能为 null (旧 QR 没 app_id)

                String t = MonitorSign.timestamp();
                String sign = MonitorSign.heartbeat(t, signKey);

                String heartUrl = url + "/appHeart?t=" + t + "&sign=" + sign +
                                  (aid != null ? "&app_id=" + aid : "");
                Request request = new Request.Builder().url(heartUrl).method("GET", null).build();
                Call call = Utils.getOkHttpClient().newCall(request);
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Request failed", e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try {
                            Log.d(TAG, "onResponse: " + response.body().string());
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing response", e);
                        }
                        isOk = true;
                    }
                });
                if (url.contains("localhost")) {
                    Toast.makeText(MainActivity.this, "配置信息错误，本机调试请访问 本机局域网IP:8080(如192.168.1.101:8080) 获取配置信息进行配置!", Toast.LENGTH_LONG).show();

                    return;
                }
                //将扫描出的信息显示出来
                txthost.setText(" 通知地址：" + url);
                txtkey.setText(" 通讯密钥：" + signKey);
                txtAppId.setText(" 应用ID：" + (aid != null ? aid : "(无)"));
                host = url;
                key = signKey;
                appId = aid != null ? aid : "";

                SharedPreferences.Editor editor = getSharedPreferences("vone", MODE_PRIVATE).edit();
                editor.putString("host", host);
                editor.putString("key", key);
                editor.putString("app_id", appId);
                editor.apply();

            }
        });
        builder.show();

    }

    //检测心跳
    public void doStart(View view) {
        if (!isOk) {
            Toast.makeText(MainActivity.this, "请您先配置!", Toast.LENGTH_SHORT).show();
            return;
        }

        String t = MonitorSign.timestamp();
        String sign = MonitorSign.heartbeat(t, key);

        String heartUrl = host + "/appHeart?t=" + t + "&sign=" + sign +
                          (appId != null && !appId.isEmpty() ? "&app_id=" + appId : "");
        Request request = new Request.Builder().url(heartUrl).method("GET", null).build();
        Call call = Utils.getOkHttpClient().newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "心跳状态错误，请检查配置是否正确!", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // 必须在 worker 线程先读取 body（response.body().string() 会触发网络 I/O，
                // 若放到 handler.post 的 Runnable 里就是主线程读网络，会抛 NetworkOnMainThreadException）
                final boolean successful = response.isSuccessful();
                final int code = response.code();
                final String body;
                try {
                    body = response.body() != null ? response.body().string() : "";
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                } finally {
                    response.close();
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (successful) {
                            Toast.makeText(MainActivity.this, "心跳正常：" + body, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "心跳失败(" + code + ")：" + body, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    //检测监听
    public void checkPush(View v) {
        if (!isNotificationListenersEnabled()) {
            Toast.makeText(MainActivity.this, "通知使用权未开启，正在跳转授权页，开启后再回来点一次检测！", Toast.LENGTH_LONG).show();
            gotoNotificationAccessSetting();
            return;
        }
        if (!NeNotificationService2.isRunning) {
            Toast.makeText(MainActivity.this, "监听服务未在运行，已尝试重启服务，请稍等几秒再点一次检测！", Toast.LENGTH_LONG).show();
            toggleNotificationListenerService(this);
            return;
        }

        Notification mNotification;
        NotificationManager mNotificationManager;
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("1",
                    "Channel1", NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.setShowBadge(true);
            mNotificationManager.createNotificationChannel(channel);

            Notification.Builder builder = new Notification.Builder(this, "1");

            mNotification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        } else {
            mNotification = new Notification.Builder(MainActivity.this)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setTicker("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .setContentTitle("V免签测试推送")
                    .setContentText("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")
                    .build();
        }
        //Toast.makeText(MainActivity.this, "已推送信息，如果权限，那么将会有下一条提示！", Toast.LENGTH_SHORT).show();

        mNotificationManager.notify(id++, mNotification);
        // 回调由监听服务发出，服务被杀/权限失效时会无声无息，所以挂个超时兜底
        testPushPending = true;
        handler.removeCallbacks(testPushTimeout);
        handler.postDelayed(testPushTimeout, TEST_PUSH_TIMEOUT_MS);
    }

    //收到测试推送回调时由监听服务调用
    public static void onTestPushReceived() {
        testPushPending = false;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(testPushTimeout);
        super.onDestroy();
    }

    //各种权限的判断
    private void toggleNotificationListenerService(Context context) {
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        pm.setComponentEnabledSetting(new ComponentName(context, NeNotificationService2.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        // 不要每次打开都显示
        // Toast.makeText(MainActivity.this, "监听服务启动中...", Toast.LENGTH_SHORT).show();
    }

    public boolean isNotificationListenersEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (int i = 0; i < names.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(names[i]);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected boolean gotoNotificationAccessSetting() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {//普通情况下找不到的时候需要再特殊处理找一次
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ComponentName cn = new ComponentName("com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity");
                intent.setComponent(cn);
                intent.putExtra(":settings:show_fragment", "NotificationAccessSettings");
                startActivity(intent);
                return true;
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            Toast.makeText(this, "对不起，您的手机暂不支持", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //扫描结果回调
        if (requestCode == Constant.REQ_QR_CODE && resultCode == RESULT_OK) {
            Bundle bundle = data.getExtras();
            String scanResult = bundle.getString(Constant.INTENT_EXTRA_KEY_QR_SCAN);

            Pattern pattern = Pattern.compile("^(https?://[^/]+)/([^/]+)(?:/([^/]+))?$");
            Matcher matcher = pattern.matcher(scanResult);
            if (!matcher.matches()) {
                Toast.makeText(MainActivity.this, "二维码错误，请您扫描网站上显示的二维码!", Toast.LENGTH_SHORT).show();
                return;
            }
            String url = matcher.group(1);
            String signKey = matcher.group(2);
            String aid = matcher.group(3);

            String t = MonitorSign.timestamp();
            String sign = MonitorSign.heartbeat(t, signKey);

            String heartUrl = url + "/appHeart?t=" + t + "&sign=" + sign +
                              (aid != null ? "&app_id=" + aid : "");
            Request request = new Request.Builder().url(heartUrl).method("GET", null).build();
            Call call = Utils.getOkHttpClient().newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Request failed", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        Log.d(TAG, "onResponse: " + response.body().string());
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing response", e);
                    }
                    isOk = true;
                }
            });

            //将扫描出的信息显示出来
            txthost.setText(" 通知地址：" + url);
            txtkey.setText(" 通讯密钥：" + signKey);
            txtAppId.setText(" 应用ID：" + (aid != null ? aid : "(无)"));
            host = url;
            key = signKey;
            appId = aid != null ? aid : "";

            SharedPreferences.Editor editor = getSharedPreferences("vone", MODE_PRIVATE).edit();
            editor.putString("host", host);
            editor.putString("key", key);
            editor.putString("app_id", appId);
            editor.apply();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case Constant.REQ_PERM_CAMERA:
                // 摄像头权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的相机访问权限", Toast.LENGTH_LONG).show();
                }
                break;
            case Constant.REQ_PERM_EXTERNAL_STORAGE:
                // 文件读写权限申请
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 获得授权
                    startQrCode(null);
                } else {
                    // 被禁止授权
                    Toast.makeText(MainActivity.this, "请至权限中心打开本应用的文件读写权限", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }
}
