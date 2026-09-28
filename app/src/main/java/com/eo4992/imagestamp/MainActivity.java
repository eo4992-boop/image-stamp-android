package com.eo4992.imagestamp;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.net.NetworkInfo;
import android.net.wifi.p2p.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 10;
    private static final int REQ_IMAGE = 11;
    private static final String PRINTER_HOST = "10.192.168.1";
    private static final int PRINTER_PORT = 1234;
    private static final int MAX_DIM = 2560;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private final IntentFilter filter = new IntentFilter();
    private TextView status;
    private Button pick;
    private Uri imageUri;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40,40,40,40);
        status = new TextView(this); status.setTextSize(16); status.setText("Image Stamp\n프린터 연결 대기");
        pick = new Button(this); pick.setText("사진 선택 후 인쇄"); pick.setEnabled(false);
        root.addView(status, new LinearLayout.LayoutParams(-1,0,1));
        root.addView(pick, new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);

        pick.setOnClickListener(v -> chooseImage());
        p2p = (WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);
        channel = p2p.initialize(this, getMainLooper(), () -> setStatus("Wi-Fi Direct 채널이 끊어졌습니다."));
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        receiver = new P2pReceiver();
        requestPermissionsIfNeeded();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_PERM);
            else startDiscovery();
        } else if (Build.VERSION.SDK_INT >= 29 &&
                   checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM);
        } else startDiscovery();
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_PERM) {
            boolean ok=true; for(int x:g) ok &= x==PackageManager.PERMISSION_GRANTED;
            if(ok) startDiscovery(); else setStatus("Wi-Fi Direct 권한이 필요합니다.");
        }
    }

    @Override protected void onResume(){ super.onResume(); if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,filter); }
    @Override protected void onPause(){ unregisterReceiver(receiver); super.onPause(); }
    @Override protected void onDestroy(){ io.shutdownNow(); super.onDestroy(); }

    private void startDiscovery() {
        setStatus("프린터 검색 중...");
        p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess(){ setStatus("프린터 검색 중..."); }
            public void onFailure(int r){ setStatus("검색 실패: "+r); }
        });
    }

    private void chooseImage() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,REQ_IMAGE);
    }

    @Override protected void onActivityResult(int r,int c,Intent d) {
        super.onActivityResult(r,c,d);
        if(r==REQ_IMAGE && c==RESULT_OK && d!=null) {
            imageUri=d.getData();
            io.execute(() -> {
                try {
                    byte[] jpeg=prepareJpeg(imageUri);
                    runOnUiThread(() -> setStatus("JPEG 준비 완료 ("+jpeg.length+" bytes). 전송 중..."));
                    sendPrint(jpeg);
                } catch(Exception e) { runOnUiThread(() -> setStatus("인쇄 실패: "+e.getMessage())); }
            });
        }
    }

    private byte[] prepareJpeg(Uri uri) throws Exception {
        InputStream in=getContentResolver().openInputStream(uri);
        Bitmap src=BitmapFactory.decodeStream(in); in.close();
        if(src==null) throw new IOException("이미지를 읽을 수 없습니다.");
        int w=src.getWidth(), h=src.getHeight();
        float scale=Math.min(1f, MAX_DIM/(float)Math.max(w,h));
        Bitmap out=src;
        if(scale<1f) out=Bitmap.createScaledBitmap(src,Math.round(w*scale),Math.round(h*scale),true);
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        if(!out.compress(Bitmap.CompressFormat.JPEG,100,b)) throw new IOException("JPEG 변환 실패");
        if(out!=src) out.recycle(); src.recycle();
        return b.toByteArray();
    }

    private byte[] makePrintPacket(int size) {
        if(size<0 || size>0xFFFFFF) throw new IllegalArgumentException("JPEG가 16MB를 초과합니다.");
        byte[] p=new byte[32];
        p[0]=(byte)0xAA; p[1]=0x53; p[2]=0x4D; p[3]=0;
        p[4]=1; p[5]=0; p[6]=0;
        p[7]=(byte)(size>>>16); p[8]=(byte)(size>>>8); p[9]=(byte)size;
        return p;
    }

    private void sendPrint(byte[] jpeg) throws Exception {
        Socket s=new Socket();
        s.connect(new InetSocketAddress(PRINTER_HOST,PRINTER_PORT),120000);
        s.setSoTimeout(120000);
        try {
            OutputStream out=new BufferedOutputStream(s.getOutputStream());
            out.write(makePrintPacket(jpeg.length));
            out.flush();
            out.write(jpeg);
            out.flush();
            runOnUiThread(() -> setStatus("인쇄 데이터 전송 완료. 프린터 응답 대기..."));
            readResponses(s);
        } finally { try{s.close();}catch(Exception ignored){} }
    }

    private void readResponses(Socket s) throws Exception {
        byte[] buf=new byte[10240]; int used=0;
        InputStream in=new BufferedInputStream(s.getInputStream());
        long end=System.currentTimeMillis()+120000;
        while(System.currentTimeMillis()<end) {
            int n=in.read(buf,used,buf.length-used);
            if(n<0) break;
            used+=n;
            while(used>=32) {
                byte[] p=Arrays.copyOfRange(buf,0,32);
                used-=32; System.arraycopy(buf,32,buf,0,used);
                handlePacket(p);
            }
            if(used==buf.length) used=0;
        }
    }

    private void handlePacket(byte[] p) {
        if(p.length<32 || (p[0]&255)!=0xAA || p[1]!=0x53 || p[2]!=0x4D) return;
        int type=p[4]&255, noti=p[5]&255, cls=p[6]&255;
        String msg="응답: type="+type+" noti="+noti+" class="+cls;
        if(cls==2) {
            switch(noti) {
                case 1: msg="프린터: 인쇄 시작"; break;
                case 2: msg="프린터: 인쇄 완료"; break;
                case 7: msg="프린터: 냉각 시작"; break;
                case 8: msg="프린터: 냉각 종료"; break;
                case 9: msg="프린터: 배터리 부족"; break;
                case 10: msg="프린터: 배터리 정상"; break;
                case 11: msg="프린터: 용지 없음"; break;
                case 12: msg="프린터: 용지 복구"; break;
                case 15: msg="프린터: 용지 걸림"; break;
                case 17: msg="프린터: 급지 오류"; break;
                case 19: msg="프린터: 데이터 오류"; break;
                case 21: msg="프린터: 용지/매체 불일치"; break;
                case 3: msg="프린터: ACK"; break;
            }
        }
        final String m=msg; runOnUiThread(() -> setStatus(m));
    }

    private void setStatus(String s){ if(status!=null) status.setText("Image Stamp\n"+s); }

    private class P2pReceiver extends BroadcastReceiver {
        public void onReceive(Context c,Intent i) {
            String a=i.getAction();
            if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                if(checkSelfPermission(Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return;
                p2p.requestPeers(channel, list -> {
                    for(WifiP2pDevice d:list.getDeviceList()) {
                        String n=(d.deviceName==null?"":d.deviceName).toUpperCase(Locale.US);
                        if(n.contains("SMP")||n.contains("IMAGESTAMP")||n.contains("IMAGE STAMP")||n.contains("PRINTER")) {
                            WifiP2pConfig cfg=new WifiP2pConfig(); cfg.deviceAddress=d.deviceAddress;
                            setStatus("프린터 연결 중: "+d.deviceName);
                            p2p.connect(channel,cfg,new WifiP2pManager.ActionListener(){
                                public void onSuccess(){ }
                                public void onFailure(int r){setStatus("연결 실패: "+r);}
                            }); break;
                        }
                    }
                });
            } else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                NetworkInfo ni=i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if(ni!=null && ni.isConnected()) {
                    p2p.requestConnectionInfo(channel, info -> {
                        setStatus("Wi-Fi Direct 연결됨");
                        pick.setEnabled(true);
                    });
                }
            }
        }
    }
}
