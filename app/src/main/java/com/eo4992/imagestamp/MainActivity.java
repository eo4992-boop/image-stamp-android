package com.eo4992.imagestamp;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.p2p.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 10;
    private static final int REQ_IMAGE = 11;

    // Confirmed from the original EI-MN930 application.
    private static final String LEGACY_PRINTER_HOST = "10.192.168.1";
    private static final int PRINTER_PORT = 1234;
    private static final int MAX_DIM = 2560;
    private static final int CONNECT_TIMEOUT_MS = 120000;
    private static final int RESPONSE_TIMEOUT_MS = 120000;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private final IntentFilter filter = new IntentFilter();
    private TextView status;
    private Button pick;
    private Button refresh;
    private Uri imageUri;
    private volatile boolean p2pConnected;
    private volatile String printerHost;
    private String printerName = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("Image Stamp");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 24);

        status = new TextView(this);
        status.setTextSize(16);
        status.setText("프린터 연결 대기");

        refresh = new Button(this);
        refresh.setText("프린터 다시 검색");
        refresh.setOnClickListener(v -> startDiscovery());

        pick = new Button(this);
        pick.setText("사진 선택 후 인쇄");
        pick.setEnabled(false);
        pick.setOnClickListener(v -> chooseImage());

        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        root.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(refresh, new LinearLayout.LayoutParams(-1, -2));
        root.addView(pick, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        p2p = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        channel = p2p.initialize(this, getMainLooper(),
                () -> setStatus("Wi-Fi Direct 채널이 끊어졌습니다. 다시 검색하세요."));

        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        receiver = new P2pReceiver();
        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_PERM);
            } else {
                startDiscovery();
            }
        } else if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
        } else {
            startDiscovery();
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == REQ_PERM) {
            boolean ok = g.length > 0;
            for (int x : g) ok &= x == PackageManager.PERMISSION_GRANTED;
            if (ok) startDiscovery();
            else setStatus("Wi-Fi Direct 권한이 필요합니다.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(receiver, filter);
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void startDiscovery() {
        if (p2p == null || channel == null) return;
        p2pConnected = false;
        printerHost = null;
        pick.setEnabled(false);
        setStatus("프린터 검색 중...");

        p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                setStatus("프린터 검색 중...");
            }
            public void onFailure(int reason) {
                setStatus("검색 실패: " + reason + " (Wi-Fi Direct가 켜져 있는지 확인)");
            }
        });
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33)
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_IMAGE);
    }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r == REQ_IMAGE && c == RESULT_OK && d != null && d.getData() != null) {
            imageUri = d.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        imageUri,
                        d.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (Exception ignored) {}

            io.execute(() -> {
                try {
                    byte[] jpeg = prepareJpeg(imageUri);
                    runOnUiThread(() ->
                            setStatus("JPEG 준비 완료 (" + jpeg.length + " bytes). 프린터로 전송 중..."));
                    sendPrint(jpeg);
                } catch (Exception e) {
                    runOnUiThread(() -> setStatus("인쇄 실패: " + errorText(e)));
                }
            });
        }
    }

    private byte[] prepareJpeg(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("이미지를 열 수 없습니다.");
        Bitmap src;
        try {
            src = BitmapFactory.decodeStream(in);
        } finally {
            in.close();
        }
        if (src == null) throw new IOException("이미지를 읽을 수 없습니다.");

        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, MAX_DIM / (float) Math.max(w, h));
        Bitmap out = src;
        if (scale < 1f)
            out = Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            if (!out.compress(Bitmap.CompressFormat.JPEG, 100, b))
                throw new IOException("JPEG 변환 실패");
            return b.toByteArray();
        } finally {
            if (out != src) out.recycle();
            src.recycle();
        }
    }

    /*
     * Original PrintPacketProtocol.SEND_COMMAND_PRINT():
     *   [0..2] AA 53 4D
     *   [4]     COMMAND_PRINT (1)
     *   [5]     notification id
     *   [6]     data class
     *   [7..9]  24-bit big-endian JPEG file size
     *   [10..25] system/job fields
     *   [26..31] reserved
     */
    private byte[] makePrintPacket(int size) {
        if (size < 0 || size > 0xFFFFFF)
            throw new IllegalArgumentException("JPEG가 16MB를 초과합니다.");

        byte[] p = new byte[32];
        p[0] = (byte) 0xAA;
        p[1] = 0x53;
        p[2] = 0x4D;
        p[3] = 0;
        p[4] = 1; // COMMAND_PRINT
        p[5] = 0; // m_nNoti_ID
        p[6] = 0; // image data class
        p[7] = (byte) (size >>> 16);
        p[8] = (byte) (size >>> 8);
        p[9] = (byte) size;
        // m_nJob_Cancel, Index, Current, Total, Error and system fields remain
        // at their original default values for a fresh print job.
        return p;
    }

    private void sendPrint(byte[] jpeg) throws Exception {
        String host = printerHost;
        if (!p2pConnected)
            throw new IOException("프린터와 Wi-Fi Direct 연결이 없습니다.");

        // The original app uses 10.192.168.1:1234. Prefer the address learned
        // from Wi-Fi Direct when available, but retain the original endpoint
        // as a fallback because this printer/app family uses a legacy fixed IP.
        if (host == null || host.length() == 0) host = LEGACY_PRINTER_HOST;

        final String target = host;
        runOnUiThread(() -> setStatus("프린터 연결 확인: " + target + ":" + PRINTER_PORT));

        Socket s = new Socket();
        try {
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(target, PRINTER_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(RESPONSE_TIMEOUT_MS);

            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            byte[] command = makePrintPacket(jpeg.length);
            out.write(command);
            out.flush();
            out.write(jpeg);
            out.flush();

            runOnUiThread(() ->
                    setStatus("인쇄 데이터 전송 완료. 프린터 응답 대기..."));
            readResponses(s);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void readResponses(Socket s) throws Exception {
        InputStream in = new BufferedInputStream(s.getInputStream());
        byte[] pending = new byte[64 * 1024];
        int used = 0;

        while (true) {
            int n;
            try {
                n = in.read(pending, used, pending.length - used);
            } catch (SocketTimeoutException e) {
                runOnUiThread(() -> setStatus("전송 후 응답 대기 시간 초과"));
                return;
            }
            if (n < 0) return;
            if (n == 0) continue;
            used += n;

            // Notification packets are 32 bytes. Data-transfer packets use
            // a different size, so only decode the fixed 32-byte control
            // packets here; this avoids corrupting framing on larger replies.
            while (used >= 32) {
                if ((pending[0] & 0xFF) != 0xAA
                        || pending[1] != 0x53 || pending[2] != 0x4D) {
                    int start = findHeader(pending, used);
                    if (start < 0) {
                        used = 0;
                        break;
                    }
                    System.arraycopy(pending, start, pending, 0, used - start);
                    used -= start;
                    if (used < 32) break;
                }

                byte[] packet = Arrays.copyOfRange(pending, 0, 32);
                used -= 32;
                System.arraycopy(pending, 32, pending, 0, used);
                handlePacket(packet);
            }

            if (used == pending.length) {
                throw new IOException("프린터 응답 버퍼가 가득 찼습니다.");
            }
        }
    }

    private int findHeader(byte[] b, int n) {
        for (int i = 1; i + 2 < n; i++) {
            if ((b[i] & 0xFF) == 0xAA && b[i + 1] == 0x53 && b[i + 2] == 0x4D)
                return i;
        }
        return -1;
    }

    private void handlePacket(byte[] p) {
        if (p.length < 32 || (p[0] & 255) != 0xAA || p[1] != 0x53 || p[2] != 0x4D)
            return;

        int type = p[4] & 255;
        int noti = p[5] & 255;
        int cls = p[6] & 255;

        String msg = "응답 수신: type=" + type + " noti=" + noti + " class=" + cls;

        if (cls == 2) {
            switch (noti) {
                case 1:  msg = "프린터: 인쇄 시작"; break;
                case 2:  msg = "프린터: 인쇄 완료"; break;
                case 3:  msg = "프린터: ACK"; break;
                case 7:  msg = "프린터: 냉각 시작"; break;
                case 8:  msg = "프린터: 냉각 종료"; break;
                case 9:  msg = "프린터: 배터리 부족"; break;
                case 10: msg = "프린터: 배터리 정상"; break;
                case 11: msg = "프린터: 용지 없음"; break;
                case 12: msg = "프린터: 용지 복구"; break;
                case 13: msg = "프린터: 펌웨어 업데이트 시작"; break;
                case 14: msg = "프린터: 펌웨어 업데이트 완료"; break;
                case 15: msg = "프린터: 용지 걸림"; break;
                case 17: msg = "프린터: 급지 오류"; break;
                case 19: msg = "프린터: 데이터 오류"; break;
                case 21: msg = "프린터: 용지/매체 불일치"; break;
            }
        }

        final String m = msg;
        runOnUiThread(() -> setStatus(m));
    }

    private String errorText(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private void setStatus(String s) {
        if (status != null)
            status.setText("Image Stamp\n" + s
                    + (printerName.length() > 0 ? "\n대상: " + printerName : ""));
    }

    private class P2pReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                int state = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                    setStatus("Wi-Fi Direct가 꺼져 있습니다.");
                }
                return;
            }

            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                if (!hasWifiPermission()) return;
                p2p.requestPeers(channel, list -> {
                    for (WifiP2pDevice d : list.getDeviceList()) {
                        String n = d.deviceName == null ? "" : d.deviceName;
                        String u = n.toUpperCase(Locale.US);
                        if (u.contains("SMP") || u.contains("IMAGESTAMP")
                                || u.contains("IMAGE STAMP") || u.contains("PRINTER")) {
                            printerName = n;
                            WifiP2pConfig cfg = new WifiP2pConfig();
                            cfg.deviceAddress = d.deviceAddress;
                            cfg.wps.setup = WpsInfo.PBC;
                            setStatus("프린터 연결 중...");
                            p2p.connect(channel, cfg, new WifiP2pManager.ActionListener() {
                                public void onSuccess() {}
                                public void onFailure(int r) {
                                    setStatus("연결 실패: " + r);
                                }
                            });
                            return;
                        }
                    }
                    setStatus("지원되는 Image Stamp 프린터를 찾지 못했습니다.");
                });
                return;
            }

            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                NetworkInfo ni;
                if (Build.VERSION.SDK_INT >= 33) {
                    ni = i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo.class);
                } else {
                    ni = (NetworkInfo) i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                }

                if (ni != null && ni.isConnected()) {
                    p2p.requestConnectionInfo(channel, info -> {
                        if (!info.groupFormed) return;

                        String host = null;
                        if (info.groupOwnerAddress != null)
                            host = info.groupOwnerAddress.getHostAddress();

                        /*
                         * A normal Wi-Fi Direct client can use the group-owner
                         * address returned here. The original printer app,
                         * however, also contains the legacy fixed endpoint
                         * 10.192.168.1:1234, so retain it when Android cannot
                         * expose a usable group-owner address.
                         */
                        if (host != null && host.length() > 0
                                && !info.isGroupOwner) {
                            printerHost = host;
                        } else {
                            printerHost = LEGACY_PRINTER_HOST;
                        }

                        p2pConnected = true;
                        pick.setEnabled(true);
                        setStatus("Wi-Fi Direct 연결됨 (" + printerHost + ")");
                    });
                } else {
                    p2pConnected = false;
                    printerHost = null;
                    pick.setEnabled(false);
                    setStatus("프린터 연결이 끊어졌습니다.");
                }
            }
        }
    }
}
