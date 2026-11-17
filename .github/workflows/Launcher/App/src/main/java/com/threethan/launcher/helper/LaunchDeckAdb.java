package com.threethan.launcher.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Launch deck: talks to the headset's own Wireless debugging (adbd) from inside the app.
 * Pair once with the pairing code + port, then connect with the connect port.
 * The key is stored privately, so pairing is only needed once.
 */
public class LaunchDeckAdb extends AbsAdbConnectionManager {
    public static final String HOST = "127.0.0.1";
    private static final String PREFS = "launchdeck_adb";
    private static LaunchDeckAdb instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;
    private final SharedPreferences prefs;

    public static synchronized LaunchDeckAdb get(Context context) throws Exception {
        if (instance == null) instance = new LaunchDeckAdb(context.getApplicationContext());
        return instance;
    }

    private LaunchDeckAdb(Context context) throws Exception {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setApi(Build.VERSION.SDK_INT);

        String k = prefs.getString("key", null);
        String c = prefs.getString("cert", null);
        PrivateKey pk = null;
        Certificate cert = null;
        if (k != null && c != null) {
            try {
                pk = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)));
                cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP)));
            } catch (Exception ignored) { pk = null; cert = null; }
        }
        if (pk == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            X500Name name = new X500Name("CN=LaunchDeck");
            Date from = new Date(System.currentTimeMillis() - 86400000L);
            Date to = new Date(System.currentTimeMillis() + 30L * 365 * 86400000L);
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(System.currentTimeMillis()), from, to, name, pair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
            cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
            pk = pair.getPrivate();
            prefs.edit()
                    .putString("key", Base64.encodeToString(pk.getEncoded(), Base64.NO_WRAP))
                    .putString("cert", Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP))
                    .apply();
        }
        privateKey = pk;
        certificate = cert;
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "LaunchDeck"; }

    // ---- saved ports ----
    public int getSavedConnectPort() { return prefs.getInt("connectPort", 0); }
    public void saveConnectPort(int port) { prefs.edit().putInt("connectPort", port).apply(); }
    public boolean hasPaired() { return prefs.getBoolean("paired", false); }
    public void setPaired() { prefs.edit().putBoolean("paired", true).apply(); }

    /** Pairs with the pairing code, then connects. Blocking: call off the main thread. */
    public void pairAndConnect(int pairingPort, String code, int connectPort) throws Exception {
        if (!pair(HOST, pairingPort, code)) throw new Exception("Pairing failed (wrong code or port?)");
        setPaired();
        connectOnly(connectPort);
    }

    /** Connects using an already-paired key. Blocking. */
    public void connectOnly(int connectPort) throws Exception {
        if (!connect(HOST, connectPort)) throw new Exception("Could not connect to port " + connectPort);
        saveConnectPort(connectPort);
    }

    /** Tries the saved connect port. Returns true if now connected. Blocking. */
    public boolean tryReconnect() {
        try {
            if (isConnected()) return true;
            int port = getSavedConnectPort();
            if (port <= 0 || !hasPaired()) return false;
            return connect(HOST, port);
        } catch (Exception e) { return false; }
    }

    /** Runs a shell command on the headset and returns its output. Blocking. */
    public String shell(String command) throws Exception {
        AdbStream stream = openStream("shell:" + command);
        try (InputStream in = stream.openInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8").trim();
        } finally {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    /** Sets the headset refresh rate. */
    public void setRefreshRate(int hz) throws Exception {
        shell("setprop debug.oculus.refreshRate " + hz);
    }
}
