package com.example.soyomesaj;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;

public class SoyoAccessibilityService extends AccessibilityService {
    private static final String PREFS = "soyo_helper_prefs";
    private static final String PREF_TEMPLATE = "template";
    private static final String PREF_TITLE = "title";
    private static final String PREF_AUTO_OPEN = "auto_open_recommendation";
    private static final String PREF_AUTO_MODE = "full_auto_mode";
    private static final String PREF_VIP_ONLY = "vip_only";
    private static final String DEFAULT_TEMPLATE = "{isim} {hitap} selamlarrrr";
    private static final String SOYO_PACKAGE = "com.haflla.soulu";
    private static final String SOYO_LITE_PACKAGE = "com.haflla.soulu.lite";

    private static final Set<String> BLOCKED_WORDS = new HashSet<>(Arrays.asList(
            "önerilen", "önerilenler", "yeniler", "mesaj", "mesajlar", "gönder", "gonder", "soyo",
            "ara", "keşfet", "kesfet", "profil", "takip", "takip et", "beğen", "begen",
            "geri", "ayarlar", "bildirimler", "çevrimiçi", "cevrimici", "online", "yeni",
            "sohbet", "sohbete başla", "sohbete basla", "merhaba", "ana sayfa", "anlar",
            "parti", "ben", "game", "sosyal olarak", "turkey", "türkiye", "kişilik benzerliği",
            "tanıştığıma memnun oldum", "tanistigima memnun oldum", "nasılsınız", "nasilsiniz",
            "istek listesi", "sonraki",
            "tag", "etiket", "etiketi", "badge", "rozet", "vip", "level", "seviye",
            "id", "uid", "nickname", "kullanıcı adı", "kullanici adi"
    ));

    private WindowManager windowManager;
    private View approvalView;
    private TextView approvalText;
    private AccessibilityNodeInfo pendingSendButton;

    // Önerilenler ekranında eşleştirilen kişi; sohbet açılınca isim için öncelikli kaynak olur.
    private String carriedRecommendationName;
    private long carriedRecommendationAt;
    private boolean carriedRecommendationVip = false;
    private String pendingName;
    private String pendingMessage;

    private String lastPreparedName = "";
    private long lastPreparedAt = 0L;
    private long lastScanAt = 0L;
    private long lastRecommendationClickAt = 0L;
    private boolean openingRecommendation = false;
    private boolean autoSendScheduled = false;
    private boolean returningToRecommendations = false;
    private long lastAutoScrollAt = 0L;
    private long automationEpoch = 0L;
    private long lastRecoveryLaunchAt = 0L;
    private long lastAutoSendProgressAt = 0L;
    private long lastReturnProgressAt = 0L;
    private long unrecognizedChatSince = 0L;
    private String lastRecommendationSnapshot = "";
    private int stagnantRecommendationScans = 0;
    private static final int AUTO_SEND_MAX_ATTEMPTS = 8;
    private static final long AUTO_SEND_RETRY_MS = 220L;
    private static final long HEADER_CONFIRM_GRACE_MS = 1800L;
    private static final long RECOVERY_RELAUNCH_COOLDOWN_MS = 4000L;
    private static final long WATCHDOG_INTERVAL_MS = 900L;
    private static final long AUTO_SEND_STALL_MS = 1800L;
    private static final long AUTO_SEND_ABSOLUTE_TIMEOUT_MS = 5000L;
    private static final long UNRECOGNIZED_CHAT_SKIP_MS = 2400L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Accessibility/Flutter aynı ekran değişiminde peş peşe çok sayıda event üretebiliyor.
    // Her event için ayrı scan kuyruğa eklenirse birkaç dakika sonra MainLooper geriden gelir;
    // bunun sonucu tıklamalar gecikmiş gibi görünür ve başlangıçtaki hız kaybolur.
    // Tek bir bekleyen tarama tutup en erken olanı çalıştırarak kuyruk birikmesini önlüyoruz.
    private boolean scanScheduled = false;
    private long scanScheduledFor = 0L;
    private static final long MIN_SCAN_GAP_MS = 75L;
    private final Runnable coalescedScan = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            scanScheduledFor = 0L;
            lastScanAt = SystemClock.elapsedRealtime();
            try {
                scanSoyoScreen();
            } catch (RuntimeException ignored) {
                recoverAfterRuntimeFailure();
            }
        }
    };

    private final Runnable automationWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (isFullAutoEnabled()) {
                    long now = SystemClock.elapsedRealtime();
                    if (returningToRecommendations) {
                        if (now - lastReturnProgressAt > 1200L) {
                            lastReturnProgressAt = now;
                            waitForRecommendationsAfterBack(0);
                        }
                    } else if (!TextUtils.isEmpty(pendingName)
                            && lastPreparedAt > 0L
                            && now - lastPreparedAt > AUTO_SEND_ABSOLUTE_TIMEOUT_MS) {
                        // Tek bir bozuk sohbet / kaybolan gönder düğmesi bütün tam otomatik
                        // akışı sonsuza kadar kilitlemesin. 5 saniye boyunca sonuç alınamadıysa
                        // bu kişiyi atla ve listeden devam et.
                        skipCurrentChatAndContinue(pendingName);
                    } else if (autoSendScheduled
                            && !TextUtils.isEmpty(pendingName)
                            && now - lastAutoSendProgressAt > AUTO_SEND_STALL_MS) {
                        // Eski gecikmeli gönderim görevlerini geçersiz kıl. Önce gönderimin
                        // gerçekleşip gerçekleşmediğini doğrula; kanıt yoksa confirm akışı güvenli
                        // şekilde yeniden gönderim turuna döner.
                        automationEpoch++;
                        lastAutoSendProgressAt = now;
                        confirmAutomaticSend(pendingName, automationEpoch, 0);
                    } else if (!autoSendScheduled
                            && !TextUtils.isEmpty(pendingName)
                            && !TextUtils.isEmpty(pendingMessage)) {
                        // Bir callback kaybolduysa mesaj ekranda hazırlanmış halde kalabilir.
                        // Körlemesine yeniden tıklamak yerine önce mevcut durumu doğrula.
                        automationEpoch++;
                        autoSendScheduled = true;
                        lastAutoSendProgressAt = now;
                        confirmAutomaticSend(pendingName, automationEpoch, 0);
                    } else {
                        requestScan(0L);
                    }
                }
            } catch (RuntimeException ignored) {
                recoverAfterRuntimeFailure();
            } finally {
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    // Aynı servis oturumunda geri dönüldüğünde aynı kişiyi hemen tekrar açmamak için.
    private final Set<String> handledRecommendationNames = new HashSet<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler.removeCallbacks(automationWatchdog);
        mainHandler.removeCallbacks(coalescedScan);
        scanScheduled = false;
        scanScheduledFor = 0L;
        mainHandler.postDelayed(automationWatchdog, WATCHDOG_INTERVAL_MS);
        Toast.makeText(this, "SOYO önerilenler algılama hazır.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!SOYO_PACKAGE.equals(pkg) && !SOYO_LITE_PACKAGE.equals(pkg)) {
            // Gecikmiş callback'in başka bir ekranda tıklama yapmasını engelle.
            automationEpoch++;
            autoSendScheduled = false;
            hideApproval();
            return;
        }

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        // Event fırtınasında doğrudan tarama yapma. Tek bekleyen taramaya birleştir;
        // böylece uzun çalışmada Handler kuyruğu büyüyüp tıklamaları geciktirmez.
        requestScan(0L);
    }

    private void recoverAfterRuntimeFailure() {
        automationEpoch++;
        autoSendScheduled = false;
        openingRecommendation = false;
        returningToRecommendations = false;
        if (isFullAutoEnabled() && !TextUtils.isEmpty(pendingName) && !TextUtils.isEmpty(pendingMessage)) {
            String expectedName = pendingName;
            long expectedEpoch = automationEpoch;
            autoSendScheduled = true;
            lastAutoSendProgressAt = SystemClock.elapsedRealtime();
            mainHandler.postDelayed(() -> confirmAutomaticSend(expectedName, expectedEpoch, 0), 240);
        } else {
            requestScan(240L);
        }
    }

    private void requestScan(long delayMs) {
        long now = SystemClock.elapsedRealtime();
        long safeDelay = Math.max(0L, delayMs);
        long sinceLast = now - lastScanAt;
        if (sinceLast < MIN_SCAN_GAP_MS) {
            safeDelay = Math.max(safeDelay, MIN_SCAN_GAP_MS - sinceLast);
        }
        long requestedFor = now + safeDelay;

        if (scanScheduled) {
            // Zaten daha erken/eşit bir tarama bekliyorsa yenisini ekleme. Daha erken bir
            // istek geldiyse eskisini iptal edip yalnızca onu öne al.
            if (scanScheduledFor <= requestedFor) return;
            mainHandler.removeCallbacks(coalescedScan);
        }

        scanScheduled = true;
        scanScheduledFor = requestedFor;
        mainHandler.postDelayed(coalescedScan, safeDelay);
    }

    private void scanSoyoScreen() {
        if (returningToRecommendations) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!isSoyoRoot(root)) {
            root.recycle();
            return;
        }

        try {
            AccessibilityNodeInfo editor = findBestEditor(root);
            if (editor != null) {
                openingRecommendation = false;
                prepareChatMessage(root, editor);
                return;
            }
            unrecognizedChatSince = 0L;

            // Mesaj kutusu yoksa Önerilenler sayfasında olabiliriz. Tam otomatik modda
            // başka SOYO sekmesine düşmüşsek Önerilen sekmesini de kendi bulup aç.
            if (isFullAutoEnabled() && maybeNavigateToRecommendations(root)) {
                return;
            }
            if (isAutoOpenEnabled() || isFullAutoEnabled()) {
                maybeOpenRecommendation(root);
            }
        } finally {
            root.recycle();
        }
    }

    private void maybeOpenRecommendation(AccessibilityNodeInfo root) {
        // Geri dönüş tamamlanmadan yeni sohbet açılırsa gecikmeli geri işlemi yeni sohbeti kapatabilir.
        if (returningToRecommendations) return;
        if (approvalView != null && approvalView.getWindowToken() != null) return;

        long now = SystemClock.elapsedRealtime();
        if (openingRecommendation && now - lastRecommendationClickAt < 2600) return;
        if (now - lastRecommendationClickAt < 650) return;

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        try {
            if (!looksLikeRecommendationsScreen(nodes)) return;

            RecommendationTarget target = findFirstRecommendationTarget(nodes);
            if (target == null) {
                if (isFullAutoEnabled()) {
                    String snapshot = buildRecommendationSnapshot(nodes);
                    if (!snapshot.isEmpty() && snapshot.equals(lastRecommendationSnapshot)) {
                        stagnantRecommendationScans++;
                    } else {
                        stagnantRecommendationScans = 0;
                        lastRecommendationSnapshot = snapshot;
                    }
                    // Aynı ekran birkaç tur değişmediyse kısa kaydırma yerine daha güçlü bir
                    // kaydırma yap. dispatchGesture(true) listenin gerçekten hareket ettiği
                    // anlamına gelmediği için bu, listenin dibinde/özel Flutter listesinde
                    // sessizce takılı kalmayı azaltır.
                    scrollRecommendationsForward(nodes, stagnantRecommendationScans);
                }
                return;
            }

            stagnantRecommendationScans = 0;
            lastRecommendationSnapshot = "";

            // Her sohbet açılışı yeni bir işlem neslidir. Eski gecikmeli görevler bu
            // token değiştiğinde artık hiçbir şeye tıklayamaz.
            automationEpoch++;
            carriedRecommendationName = target.name;
            carriedRecommendationAt = now;
            carriedRecommendationVip = target.vip;
            lastRecommendationClickAt = now;
            openingRecommendation = true;

            boolean clicked = clickNodeOrParent(target.chatButton);
            if (!clicked) {
                openingRecommendation = false;
                carriedRecommendationName = null;
                carriedRecommendationAt = 0L;
                carriedRecommendationVip = false;
            }
            recycleNode(target.chatButton);
        } finally {
            recycleNodes(nodes);
        }
    }

    private void scrollRecommendationsForward(List<AccessibilityNodeInfo> nodes, int stagnantScans) {
        long now = SystemClock.elapsedRealtime();
        // Normal akışta hızlı kal; aynı ekran üst üste görülüyorsa lazy-load / Flutter animasyonu
        // için biraz daha nefes bırak. Böylece arka arkaya gesture gönderip listeyi kilitlemeyiz.
        long minGap = stagnantScans >= 4 ? 720L : 620L;
        if (now - lastAutoScrollAt < minGap) return;

        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            boolean supportsScroll = node.isScrollable()
                    || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (!supportsScroll) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int area = Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) {
                recycleNode(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestArea = area;
            }
        }

        boolean accepted = false;

        // Çok uzun süre aynı snapshot'ta kalırsak mevcut Önerilen sekmesine tekrar dokunmak
        // bazı SOYO/Flutter sürümlerinde listenin scroll controller'ını ve lazy-load'ı uyandırıyor.
        // Gesture çoğu cihazda hareket etmese bile true dönebildiği için bu kurtarmayı gesture
        // sonucuna bağlamıyoruz; belirli turlarda doğrudan önce sekmeyi yeniden odaklıyoruz.
        if (stagnantScans >= 9 && stagnantScans % 6 == 3) {
            accepted = tapRecommendationsTab(nodes);
        }

        // ÖNEMLİ: dispatchGesture() true döndüğünde yalnızca Android hareketi kabul etmiş olur;
        // Flutter/özel listede içerik gerçekten kaymış olmak zorunda değildir. Önceki sürüm bu
        // true değerini hareket kanıtı saydığı için uzun kullanımda aynı ekranda sonsuza kadar
        // kalabiliyordu. Snapshot değişmiyorsa farklı kaydırma yollarını sırayla deniyoruz.
        if (!accepted && stagnantScans >= 2 && best != null && stagnantScans % 3 == 2) {
            accepted = best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }

        if (!accepted) {
            int recoveryLevel;
            if (stagnantScans >= 5) recoveryLevel = 2;       // lazy-load için uzun hareket
            else if (stagnantScans >= 2) recoveryLevel = 1;  // güçlü hareket
            else recoveryLevel = 0;                          // kısa, örtüşmeli hareket
            accepted = swipeRecommendationsUp(recoveryLevel, stagnantScans);
        }

        recycleNode(best);
        if (accepted) {
            lastAutoScrollAt = now;
            long rescanDelay = stagnantScans >= 5 ? 400L : 300L;
            requestScan(rescanDelay);
            // Lazy-load geç gelirse ek kontrol iste; requestScan bunları tek kuyruğa
            // birleştirdiği için eski sürümdeki gibi yüzlerce gecikmiş tarama birikmez.
            if (stagnantScans >= 4) {
                requestScan(900L);
            }
        } else {
            // Hiçbir yöntem kabul edilmediyse watchdog'u beklemeden kısa bir tekrar kontrolü yap.
            requestScan(280L);
        }
    }

    private boolean tapRecommendationsTab(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return false;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String text = normalizedLower(nodeText(node));
            String desc = normalizedLower(safe(node.getContentDescription()));
            if (!("önerilen".equals(text) || "önerilenler".equals(text)
                    || "önerilen".equals(desc) || "önerilenler".equals(desc))) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty() || r.centerY() < screenHeight * 0.55f) continue;
            if (r.centerY() > bestY) {
                recycleNode(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestY = r.centerY();
            }
        }
        boolean clicked = clickNodeOrParent(best);
        recycleNode(best);
        return clicked;
    }

    private boolean swipeRecommendationsUp(int recoveryLevel, int stagnantScans) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        // Aynı x çizgisinde bir overlay/gesture-detector hareketi yutuyorsa kurtarma turlarında
        // parmağı hafif sağ/sol kaydır. Düğmelerin üstünde başlamamak için merkez bölgeden çıkmıyoruz.
        float xFactor = 0.50f;
        if (recoveryLevel >= 1) xFactor = (stagnantScans % 2 == 0) ? 0.42f : 0.58f;
        float x = width * xFactor;

        float startY;
        float endY;
        int duration;
        if (recoveryLevel >= 2) {
            startY = height * 0.86f;
            endY = height * 0.22f;
            duration = 340;
        } else if (recoveryLevel == 1) {
            startY = height * 0.80f;
            endY = height * 0.36f;
            duration = 270;
        } else {
            startY = height * 0.72f;
            endY = height * 0.56f;
            duration = 190;
        }

        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                // "completed" içerik hareket etti demek değildir; sadece taramayı hızlandırır.
                requestScan(70L);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                requestScan(120L);
            }
        }, null);
    }

    private String buildRecommendationSnapshot(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return "";
        StringBuilder out = new StringBuilder();
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String text = nodeText(node).trim();
            String desc = safe(node.getContentDescription()).trim();
            if (text.isEmpty() && desc.isEmpty()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty()) continue;
            String token = !text.isEmpty() ? text : desc;
            token = Normalizer.normalize(token, Normalizer.Form.NFKC)
                    .replaceAll("\\s+", " ").trim();
            if (token.length() > 32) token = token.substring(0, 32);
            out.append(token).append('@').append(r.top / Math.max(1, dp(12))).append('|');
            if (out.length() > 700) break;
        }
        return out.toString();
    }

    private boolean looksLikeRecommendationsScreen(List<AccessibilityNodeInfo> nodes) {
        boolean hasRecommendationsHeader = false;
        boolean hasRecommendationsLabel = false;
        boolean hasChatAction = false;
        boolean hasVisibleEditor = false;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int headerCutoff = (int) (screenHeight * 0.48f);
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            if (node.isEditable()) hasVisibleEditor = true;
            String text = normalizedLower(nodeText(node));
            String desc = normalizedLower(safe(node.getContentDescription()));
            if (isChatLabel(text) || isChatLabel(desc)) hasChatAction = true;
            if ("önerilen".equals(text) || "önerilenler".equals(text)
                    || "önerilen".equals(desc) || "önerilenler".equals(desc)) {
                hasRecommendationsLabel = true;
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty() && r.centerY() < headerCutoff) hasRecommendationsHeader = true;
            }
        }
        // Alt menüdeki "Önerilen" sekmesini sayfa başlığı sanma. Sohbet editörü görünüyorsa
        // da kesinlikle öneri listesinde değiliz.
        return !hasVisibleEditor
                && (hasRecommendationsHeader || (hasRecommendationsLabel && hasChatAction));
    }

    private boolean maybeNavigateToRecommendations(AccessibilityNodeInfo root) {
        if (root == null || !isFullAutoEnabled()) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        try {
            if (looksLikeRecommendationsScreen(nodes)) return false;
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null && node.isVisibleToUser() && node.isEditable()) return false;
            }

            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            AccessibilityNodeInfo best = null;
            int bestY = -1;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String text = normalizedLower(nodeText(node));
                String desc = normalizedLower(safe(node.getContentDescription()));
                if (!("önerilen".equals(text) || "önerilenler".equals(text)
                        || "önerilen".equals(desc) || "önerilenler".equals(desc))) continue;
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.isEmpty() || r.centerY() < screenHeight * 0.55f) continue;
                if (r.centerY() > bestY) {
                    recycleNode(best);
                    best = AccessibilityNodeInfo.obtain(node);
                    bestY = r.centerY();
                }
            }
            boolean clicked = clickNodeOrParent(best);
            recycleNode(best);
            if (clicked) {
                openingRecommendation = false;
                requestScan(120L);
            }
            return clicked;
        } finally {
            recycleNodes(nodes);
        }
    }

    private RecommendationTarget findFirstRecommendationTarget(List<AccessibilityNodeInfo> nodes) {
        List<RecommendationTarget> targets = new ArrayList<>();

        for (AccessibilityNodeInfo chatNode : nodes) {
            if (chatNode == null || !chatNode.isVisibleToUser()) continue;
            String text = normalizedLower(nodeText(chatNode));
            String desc = normalizedLower(safe(chatNode.getContentDescription()));
            if (!isChatLabel(text) && !isChatLabel(desc)) continue;

            Rect chatRect = new Rect();
            chatNode.getBoundsInScreen(chatRect);
            if (chatRect.isEmpty()) continue;

            NameCandidate rowName = findNameLeftOfChat(nodes, chatRect);
            if (rowName == null) continue;
            if (handledRecommendationNames.contains(nameKey(rowName.name))) continue;
            boolean rowVip = isVipRecommendationRow(nodes, chatRect);
            if (isVipOnlyEnabled() && !rowVip) continue;

            targets.add(new RecommendationTarget(
                    rowName.name,
                    AccessibilityNodeInfo.obtain(chatNode),
                    new Rect(chatRect),
                    rowName.score,
                    rowVip
            ));
        }

        RecommendationTarget best = null;
        for (RecommendationTarget target : targets) {
            if (best == null
                    || target.chatRect.top < best.chatRect.top
                    || (target.chatRect.top == best.chatRect.top && target.score > best.score)) {
                recycleTarget(best);
                best = target;
            } else {
                recycleTarget(target);
            }
        }
        return best;
    }

    private boolean isVipRecommendationRow(List<AccessibilityNodeInfo> nodes, Rect chatRect) {
        if (nodes == null || chatRect == null || chatRect.isEmpty()) return false;
        int rowTolerance = Math.max(dp(34), Math.min(dp(50), chatRect.height() + dp(14)));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty()) continue;
            if (Math.abs(r.centerY() - chatRect.centerY()) > rowTolerance) continue;
            if (r.right < dp(8) || r.left > screenWidth - dp(8)) continue;

            String label = nodeText(node) + " " + safe(node.getContentDescription());
            if (containsVipMarker(label)) return true;
        }
        return false;
    }

    private boolean containsVipMarker(String raw) {
        if (TextUtils.isEmpty(raw)) return false;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.matches(".*(^|[^a-z0-9])vip\\s*[0-9]*([^a-z0-9]|$).*");
    }


    private boolean hasVipMarkerInChatHeader(AccessibilityNodeInfo root, AccessibilityNodeInfo editor) {
        if (root == null || editor == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);

        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        Rect editorRect = new Rect();
        editor.getBoundsInScreen(editorRect);
        if (rootRect.isEmpty()) {
            rootRect.set(0, 0, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }

        int headerBottom = rootRect.top + Math.max(dp(160), rootRect.height() / 4);
        boolean found = false;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty() || r.top > headerBottom || r.bottom >= editorRect.top) continue;
            String label = nodeText(node) + " " + safe(node.getContentDescription());
            if (containsVipMarker(label)) {
                found = true;
                break;
            }
        }
        recycleNodes(nodes);
        return found;
    }
    /**
     * Ekran görüntüsündeki satır düzenine göre isim, sarı "Sohbet" düğmesinin solunda ve yaklaşık
     * aynı dikey merkezde. VIP/km/yaş rozetleri rakam içerdiği için normalizeNameCandidate tarafından
     * zaten elenir.
     */
    private NameCandidate findNameLeftOfChat(List<AccessibilityNodeInfo> nodes, Rect chatRect) {
        NameCandidate best = null;
        // Komşu satırın adını alma riskini azalt: tipik öneri satırları birbirinden
        // 60-90dp ayrılıyor. 48dp üstünü aynı satır kabul etmiyoruz.
        int maxVerticalDistance = Math.max(dp(36), Math.min(dp(48), chatRect.height() + dp(12)));

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            String raw = nodeText(node).trim();
            String name = normalizeNameCandidate(raw);
            if (name == null) continue;

            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty()) continue;

            // İsim düğmenin solunda olmalı; çok uzakta ya da başka satırda olmamalı.
            if (r.left >= chatRect.left) continue;
            if (r.right > chatRect.left + dp(36)) continue;
            int verticalDistance = Math.abs(r.centerY() - chatRect.centerY());
            if (verticalDistance > maxVerticalDistance) continue;

            int score = 40;
            score -= verticalDistance / Math.max(1, dp(4));
            int horizontalGap = Math.max(0, chatRect.left - r.right);
            score -= Math.min(12, horizontalGap / Math.max(1, dp(28)));
            if (r.centerY() <= chatRect.centerY() + dp(18)) score += 4;
            if (raw.equals(name)) score += 2;
            if (raw.length() >= 3) score += 2;

            if (best == null || score > best.score) {
                best = new NameCandidate(name, score);
            }
        }
        return best;
    }

    private void prepareChatMessage(AccessibilityNodeInfo root, AccessibilityNodeInfo editor) {
        String existing = nodeText(editor);
        if (!existing.trim().isEmpty()) {
            // Tam otomatik modda daha önce kullanıcı tarafından bırakılmış bir taslak, eski bir
            // hızlı yanıt veya SOYO'nun geri yüklediği metin varsa eski kod burada sonsuza kadar
            // bekliyordu. Kullanıcının taslağını silme; bu kişiyi güvenli biçimde atlayıp devam et.
            if (isFullAutoEnabled() && TextUtils.isEmpty(pendingName)) {
                NameCandidate header = findChatHeaderName(root, editor);
                String skipName = header != null ? header.name : carriedRecommendationName;
                recycleNode(editor);
                skipCurrentChatAndContinue(skipName);
                return;
            }
            recycleNode(editor);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        NameCandidate header = findChatHeaderName(root, editor);
        boolean vipOnly = isVipOnlyEnabled();
        boolean chatHeaderVip = vipOnly && hasVipMarkerInChatHeader(root, editor);
        boolean freshCarriedVip = carriedRecommendationVip
                && !TextUtils.isEmpty(carriedRecommendationName)
                && now - carriedRecommendationAt < 12000;
        NameCandidate candidate = null;

        // VIP filtresi açıkken sohbetin VIP olduğu ya öneri satırından güncel ve güvenli
        // biçimde taşınmış olmalı ya da sohbet başlığında VIP rozeti görünmeli.
        if (vipOnly && !freshCarriedVip && !chatHeaderVip) {
            carriedRecommendationName = null;
            carriedRecommendationAt = 0L;
            recycleNode(editor);
            if (isFullAutoEnabled()) mainHandler.postDelayed(this::returnToRecommendations, 120);
            return;
        }

        // Liste satırından taşıdığımız isim sadece sohbet başlığıyla doğrulanır. Böylece
        // gecikmiş olay / kaymış liste / yanlış satır tıklaması başka kişinin adını gönderemez.
        if (!TextUtils.isEmpty(carriedRecommendationName) && now - carriedRecommendationAt < 12000) {
            if (header != null) {
                if (samePersonName(carriedRecommendationName, header.name)) {
                    candidate = new NameCandidate(header.name, Math.max(100, header.score));
                } else {
                    // Gerçek sohbet başlığı liste bilgisinden daha güvenilir. VIP filtresi
                    // açıksa yanlış satır/yanlış sohbet eşleşmesinde VIP bilgisini körlemesine
                    // taşımayız; gerçek sohbet başlığında VIP görünmüyorsa bu sohbeti atlarız.
                    if (vipOnly && !chatHeaderVip) {
                        carriedRecommendationName = null;
                        carriedRecommendationAt = 0L;
                        carriedRecommendationVip = false;
                        recycleNode(editor);
                        if (isFullAutoEnabled()) mainHandler.postDelayed(this::returnToRecommendations, 120);
                        return;
                    }
                    candidate = new NameCandidate(header.name, Math.max(90, header.score));
                    carriedRecommendationName = header.name;
                    carriedRecommendationAt = now;
                    carriedRecommendationVip = chatHeaderVip;
                }
            } else if (now - carriedRecommendationAt < HEADER_CONFIRM_GRACE_MS) {
                // Başlığın çizilmesi için çok kısa bekle; eski ismi körlemesine yazma.
                recycleNode(editor);
                requestScan(100L);
                return;
            } else {
                NameCandidate fallback = findBestName(root, editor);
                if (fallback == null || samePersonName(carriedRecommendationName, fallback.name)) {
                    candidate = new NameCandidate(carriedRecommendationName, 60);
                } else if (!isFullAutoEnabled() && fallback.score >= 14) {
                    candidate = fallback;
                } else {
                    // Otomatik modda isim çelişkisi varsa mesaj göndermek yerine yeniden tara.
                    recycleNode(editor);
                    requestScan(130L);
                    return;
                }
            }
        } else {
            candidate = header;
        }

        if (candidate == null && !isFullAutoEnabled()) candidate = findBestName(root, editor);
        if (candidate == null || TextUtils.isEmpty(candidate.name) || candidate.score < 8) {
            if (isFullAutoEnabled()) {
                if (unrecognizedChatSince == 0L) unrecognizedChatSince = now;
                if (now - unrecognizedChatSince >= UNRECOGNIZED_CHAT_SKIP_MS) {
                    String skipName = carriedRecommendationName;
                    recycleNode(editor);
                    skipCurrentChatAndContinue(skipName);
                    return;
                }
                recycleNode(editor);
                requestScan(120L);
                return;
            }
            recycleNode(editor);
            return;
        }
        unrecognizedChatSince = 0L;

        // Gönderilmiş bir sohbetten listeye dönüş gecikirse aynı kişiye ikinci kez mesaj atma.
        if (isFullAutoEnabled() && handledRecommendationNames.contains(nameKey(candidate.name))) {
            recycleNode(editor);
            skipCurrentChatAndContinue(candidate.name);
            return;
        }

        if (candidate.name.equalsIgnoreCase(lastPreparedName) && now - lastPreparedAt < 3500) {
            recycleNode(editor);
            return;
        }

        String message = buildMessage(candidate.name);
        if (!setNodeText(editor, message)) {
            recycleNode(editor);
            return;
        }

        AccessibilityNodeInfo send = findSendButton(root, editor);
        recycleNode(editor);
        pendingName = candidate.name;
        pendingMessage = message;
        replacePendingSendButton(send);
        lastPreparedName = candidate.name;
        lastPreparedAt = now;
        carriedRecommendationName = null;
        carriedRecommendationAt = 0L;
        carriedRecommendationVip = false;
        if (isFullAutoEnabled()) {
            removeApprovalOverlayOnly();
            scheduleAutomaticSend(candidate.name, automationEpoch);
        } else {
            showApproval(candidate.name, message, send != null);
        }
    }

    private void skipCurrentChatAndContinue(String handledName) {
        automationEpoch++;
        autoSendScheduled = false;
        openingRecommendation = false;
        unrecognizedChatSince = 0L;
        if (!TextUtils.isEmpty(handledName)) markHandled(handledName);
        carriedRecommendationName = null;
        carriedRecommendationAt = 0L;
        carriedRecommendationVip = false;
        recycleNode(pendingSendButton);
        pendingSendButton = null;
        pendingName = null;
        pendingMessage = null;
        removeApprovalOverlayOnly();
        mainHandler.postDelayed(this::returnToRecommendations, 100);
    }

    private void scheduleAutomaticSend(String expectedName, long expectedEpoch) {
        if (autoSendScheduled) return;
        autoSendScheduled = true;
        lastAutoSendProgressAt = SystemClock.elapsedRealtime();
        mainHandler.postDelayed(() -> attemptAutomaticSend(expectedName, expectedEpoch, 0), 170);
    }

    private void attemptAutomaticSend(String expectedName, long expectedEpoch, int attempt) {
        lastAutoSendProgressAt = SystemClock.elapsedRealtime();
        if (!isFullAutoEnabled() || TextUtils.isEmpty(expectedName)
                || !expectedName.equals(pendingName) || expectedEpoch != automationEpoch) {
            autoSendScheduled = false;
            return;
        }

        AccessibilityNodeInfo freshRoot = getRootInActiveWindow();
        AccessibilityNodeInfo freshEditor = null;
        AccessibilityNodeInfo freshSend = null;
        Rect editorRect = null;
        boolean clicked = false;

        if (freshRoot != null && isSoyoRoot(freshRoot)) {
            freshEditor = findBestEditor(freshRoot);
            if (freshEditor != null) {
                NameCandidate liveHeader = findChatHeaderName(freshRoot, freshEditor);
                if (liveHeader != null && !samePersonName(expectedName, liveHeader.name)) {
                    // Sohbet değişmiş: eski mesajı kesinlikle gönderme. Aynı ekranda doğru ada
                    // göre kendini toparlayıp yeniden hazırla.
                    String oldMessage = pendingMessage;
                    if (oldMessage != null && oldMessage.equals(nodeText(freshEditor))) {
                        setNodeText(freshEditor, "");
                    }
                    automationEpoch++;
                    autoSendScheduled = false;
                    recycleNode(pendingSendButton);
                    pendingSendButton = null;
                    pendingName = null;
                    pendingMessage = null;
                    lastPreparedName = "";
                    lastPreparedAt = 0L;
                    carriedRecommendationName = liveHeader.name;
                    carriedRecommendationAt = SystemClock.elapsedRealtime();
                    carriedRecommendationVip = hasVipMarkerInChatHeader(freshRoot, freshEditor);
                    recycleNode(freshEditor);
                    freshRoot.recycle();
                    requestScan(90L);
                    return;
                }

                String editorText = nodeText(freshEditor).trim();
                if (!TextUtils.equals(editorText, pendingMessage)) {
                    // UI yeniden çizildi veya eski bir taslak kaldı: doğru mesajı tekrar koy,
                    // aynı turda tıklamayıp bir sonraki kısa turda doğrula.
                    setNodeText(freshEditor, pendingMessage);
                    recycleNode(freshEditor);
                    freshRoot.recycle();
                    if (attempt + 1 < AUTO_SEND_MAX_ATTEMPTS) {
                        mainHandler.postDelayed(() -> attemptAutomaticSend(expectedName, expectedEpoch, attempt + 1), 100);
                    } else {
                        autoSendScheduled = false;
                        mainHandler.postDelayed(() -> scheduleAutomaticSend(expectedName, expectedEpoch), 260);
                    }
                    return;
                }

                editorRect = new Rect();
                freshEditor.getBoundsInScreen(editorRect);
                freshSend = findSendButton(freshRoot, freshEditor);
                clicked = clickNodeOrParent(freshSend);
                if (!clicked && editorRect != null && !editorRect.isEmpty()) {
                    clicked = tapLikelySendButton(editorRect);
                }
            }
        }

        recycleNode(freshSend);
        recycleNode(freshEditor);
        if (freshRoot != null) freshRoot.recycle();

        if (clicked) {
            // dispatchGesture yalnızca hareketin kabul edildiğini söyler. Mesajın gerçekten
            // gittiğini doğrulamadan kişiyi işlendi sayma veya geri çıkma.
            mainHandler.postDelayed(() -> confirmAutomaticSend(expectedName, expectedEpoch, 0), 150);
            return;
        }

        if (attempt + 1 < AUTO_SEND_MAX_ATTEMPTS) {
            mainHandler.postDelayed(() -> attemptAutomaticSend(expectedName, expectedEpoch, attempt + 1), AUTO_SEND_RETRY_MS);
            return;
        }

        autoSendScheduled = false;
        mainHandler.postDelayed(() -> {
            if (isFullAutoEnabled() && expectedEpoch == automationEpoch && expectedName.equals(pendingName)) {
                scheduleAutomaticSend(expectedName, expectedEpoch);
            }
        }, 320);
    }

    private void confirmAutomaticSend(String expectedName, long expectedEpoch, int attempt) {
        lastAutoSendProgressAt = SystemClock.elapsedRealtime();
        if (!isFullAutoEnabled() || expectedEpoch != automationEpoch
                || !expectedName.equals(pendingName)) {
            autoSendScheduled = false;
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isSoyoRoot(root)) {
            if (root != null) root.recycle();
            autoSendScheduled = false;
            return;
        }

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        boolean alreadyBack = looksLikeRecommendationsScreen(nodes);
        recycleNodes(nodes);
        if (alreadyBack) {
            root.recycle();
            finishAutomaticSend(expectedName, expectedEpoch, true);
            return;
        }

        AccessibilityNodeInfo editor = findBestEditor(root);
        if (editor != null) {
            NameCandidate header = findChatHeaderName(root, editor);
            if (header != null && !samePersonName(expectedName, header.name)) {
                String oldMessage = pendingMessage;
                if (oldMessage != null && oldMessage.equals(nodeText(editor))) {
                    setNodeText(editor, "");
                }
                automationEpoch++;
                autoSendScheduled = false;
                recycleNode(pendingSendButton);
                pendingSendButton = null;
                pendingName = null;
                pendingMessage = null;
                lastPreparedName = "";
                lastPreparedAt = 0L;
                carriedRecommendationName = header.name;
                carriedRecommendationAt = SystemClock.elapsedRealtime();
                carriedRecommendationVip = hasVipMarkerInChatHeader(root, editor);
                recycleNode(editor);
                root.recycle();
                requestScan(90L);
                return;
            }
            String current = nodeText(editor).trim();
            boolean sent = !TextUtils.equals(current, pendingMessage) || hasExactNonEditableText(root, pendingMessage);
            recycleNode(editor);
            root.recycle();
            if (sent) {
                finishAutomaticSend(expectedName, expectedEpoch, false);
                return;
            }
        } else {
            root.recycle();
        }

        if (attempt < 4) {
            mainHandler.postDelayed(() -> confirmAutomaticSend(expectedName, expectedEpoch, attempt + 1), 140);
        } else {
            // Tıklama kabul edildi ama gönderim kanıtı yoksa aynı mesajı tekrar körlemesine
            // tıklamak yerine kısa bir yeni bulma turu başlat.
            autoSendScheduled = false;
            mainHandler.postDelayed(() -> scheduleAutomaticSend(expectedName, expectedEpoch), 240);
        }
    }

    private void finishAutomaticSend(String expectedName, long expectedEpoch, boolean alreadyOnRecommendations) {
        if (expectedEpoch != automationEpoch) return;
        autoSendScheduled = false;
        lastAutoSendProgressAt = 0L;
        markHandled(expectedName);
        hideApproval();
        if (alreadyOnRecommendations) {
            openingRecommendation = false;
            returningToRecommendations = false;
            lastReturnProgressAt = 0L;
            requestScan(100L);
        } else {
            mainHandler.postDelayed(this::returnToRecommendations, 180);
        }
    }

    private boolean hasExactNonEditableText(AccessibilityNodeInfo root, String expected) {
        if (root == null || TextUtils.isEmpty(expected)) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        boolean found = false;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || node.isEditable() || !node.isVisibleToUser()) continue;
            if (expected.equals(nodeText(node).trim())) {
                found = true;
                break;
            }
        }
        recycleNodes(nodes);
        return found;
    }

    private boolean tapLikelySendButton(Rect editorRect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || editorRect == null || editorRect.isEmpty()) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        float x = Math.min(width - dp(24), Math.max(editorRect.right + dp(34), width - dp(42)));
        float y = editorRect.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription tap =
                new GestureDescription.StrokeDescription(path, 0, 70);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(tap).build();
        return dispatchGesture(gesture, null, null);
    }

    private void returnToRecommendations() {
        if (!isFullAutoEnabled() || returningToRecommendations) return;
        returningToRecommendations = true;
        openingRecommendation = false;
        lastReturnProgressAt = SystemClock.elapsedRealtime();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            mainHandler.postDelayed(() -> waitForRecommendationsAfterBack(0), 180);
            return;
        }

        if (!isSoyoRoot(root)) {
            root.recycle();
            mainHandler.postDelayed(() -> waitForRecommendationsAfterBack(0), 180);
            return;
        }

        // SOYO bazen gönderimden sonra kendisi listeye dönebiliyor. Bu durumda ekstra BACK
        // uygulamayı kapatıyordu; önce mevcut ekranı kesin olarak kontrol et.
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        boolean alreadyRecommendations = looksLikeRecommendationsScreen(nodes);
        recycleNodes(nodes);
        if (alreadyRecommendations) {
            root.recycle();
            returningToRecommendations = false;
            lastReturnProgressAt = 0L;
            requestScan(100L);
            return;
        }

        AccessibilityNodeInfo editor = findBestEditor(root);
        boolean definitelyChat = editor != null;
        recycleNode(editor);
        if (!definitelyChat) {
            root.recycle();
            // Bilinmeyen ekranda körlemesine geri basma. Ekranın oturmasını bekle.
            mainHandler.postDelayed(() -> waitForRecommendationsAfterBack(0), 180);
            return;
        }

        AccessibilityNodeInfo backButton = findTopLeftBackButton(root);
        boolean clicked = clickNodeOrParent(backButton);
        recycleNode(backButton);
        root.recycle();
        if (!clicked) {
            // Sadece hâlâ doğrulanmış sohbet ekranındayken tek global BACK kullanılır.
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        mainHandler.postDelayed(() -> waitForRecommendationsAfterBack(0), 220);
    }

    private void waitForRecommendationsAfterBack(int attempt) {
        lastReturnProgressAt = SystemClock.elapsedRealtime();
        if (!isFullAutoEnabled()) {
            returningToRecommendations = false;
            lastReturnProgressAt = 0L;
            return;
        }

        boolean onRecommendations = false;
        boolean onSoyo = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            onSoyo = isSoyoRoot(root);
            if (onSoyo) {
                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                collectNodes(root, nodes, 0);
                onRecommendations = looksLikeRecommendationsScreen(nodes);
                recycleNodes(nodes);
            }
            root.recycle();
        }

        if (onRecommendations) {
            returningToRecommendations = false;
            lastReturnProgressAt = 0L;
            requestScan(100L);
            return;
        }

        // Yalnızca botun kendi geri-dönüş kurtarma aşamasında SOYO yanlışlıkla arka plana
        // düştüyse bir kez tekrar aç. Normal kullanıcı uygulama değişiminde bunu yapmayız.
        if (!onSoyo && attempt >= 2) relaunchSoyoForRecovery();

        if (attempt < 10) {
            mainHandler.postDelayed(() -> waitForRecommendationsAfterBack(attempt + 1), 220);
        } else {
            returningToRecommendations = false;
            lastReturnProgressAt = 0L;
            requestScan(140L);
        }
    }

    private void relaunchSoyoForRecovery() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecoveryLaunchAt < RECOVERY_RELAUNCH_COOLDOWN_MS) return;
        lastRecoveryLaunchAt = now;
        Intent launch = getPackageManager().getLaunchIntentForPackage(SOYO_PACKAGE);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(SOYO_LITE_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(launch);
            } catch (Exception ignored) {
            }
        }
    }

    private AccessibilityNodeInfo findTopLeftBackButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (screen.isEmpty()) {
            screen.set(0, 0, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }

        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty() || r.centerX() > screen.left + screen.width() / 3
                    || r.centerY() > screen.top + Math.max(dp(190), screen.height() / 5)) continue;

            String label = normalizedLower(nodeText(node) + " "
                    + safe(node.getContentDescription()) + " " + safe(node.getViewIdResourceName()));
            boolean namedBack = label.contains("geri") || label.contains("back")
                    || label.contains("navigate up") || label.contains("up button");
            boolean knownNotBack = label.contains("profil") || label.contains("profile")
                    || label.contains("avatar") || label.contains("foto") || label.contains("photo");
            if (knownNotBack && !namedBack) continue;
            AccessibilityNodeInfo clickableParent = node.isClickable() ? null : findClickableParent(node);
            boolean hasClickTarget = node.isClickable() || clickableParent != null;
            recycleNode(clickableParent);
            boolean plausibleArrow = hasClickTarget
                    && r.centerX() <= screen.left + dp(92)
                    && r.centerY() <= screen.top + dp(145)
                    && r.width() >= dp(24) && r.width() <= dp(90)
                    && r.height() >= dp(24) && r.height() <= dp(90);
            if (!namedBack && !plausibleArrow) continue;

            int score = namedBack ? 80 : 20;
            if (node.isClickable()) score += 12;
            score += Math.max(0, 20 - (r.left - screen.left) / Math.max(1, dp(8)));
            score += Math.max(0, 12 - (r.top - screen.top) / Math.max(1, dp(16)));
            if (score > bestScore) {
                recycleNode(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
        }
        recycleNodes(nodes);
        return best;
    }

    private boolean isSoyoRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        return SOYO_PACKAGE.equals(pkg) || SOYO_LITE_PACKAGE.equals(pkg);
    }

    private boolean samePersonName(String a, String b) {
        String na = normalizeNameCandidate(a);
        String nb = normalizeNameCandidate(b);
        if (na == null || nb == null) return false;
        return nameKey(na).equals(nameKey(nb));
    }

    private boolean isAutoOpenEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_OPEN, true);
    }

    private boolean isFullAutoEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTO_MODE, false);
    }

    private boolean isVipOnlyEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_VIP_ONLY, false);
    }

    private AccessibilityNodeInfo findBestEditor(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            CharSequence cls = node.getClassName();
            boolean editLike = node.isEditable() || (cls != null && cls.toString().contains("EditText"));
            if (!editLike) continue;

            int score = 0;
            if (node.isEditable()) score += 8;
            if (node.isFocusable()) score += 2;
            String hint = normalizedLower(safe(node.getHintText()));
            String desc = normalizedLower(safe(node.getContentDescription()));
            String joined = hint + " " + desc;
            boolean messageHint = joined.contains("mesaj") || joined.contains("message") || joined.contains("yaz");
            if (messageHint) score += 14;
            if (joined.contains("ara") || joined.contains("search")) score -= 18;

            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.top > 0) score += Math.min(5, r.top / Math.max(1, dp(100)));
            if (!r.isEmpty() && r.centerY() > screenHeight * 0.60f) score += 8;
            if (!r.isEmpty() && r.centerY() > screenHeight * 0.76f) score += 5;

            if (score > bestScore) {
                recycleNode(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
        }
        recycleNodes(nodes);
        // Üstteki arama alanını sohbet mesaj kutusu sanmamak için minimum güven puanı.
        if (bestScore < 15) {
            recycleNode(best);
            return null;
        }
        return best;
    }

    /**
     * Kullanıcının gönderdiği sohbet ekranına özel başlık algılama. Başlık ekranın üst kısmında,
     * yatay merkeze yakın. SOYO bazen adı VIP rozetiyle tek erişilebilirlik metninde birleştirebilir
     * (ör. "Murat VIP2"); bu nedenle VIP/level son ekleri önce temizlenir.
     */
    private NameCandidate findChatHeaderName(AccessibilityNodeInfo root, AccessibilityNodeInfo editor) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);

        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        Rect editorRect = new Rect();
        editor.getBoundsInScreen(editorRect);
        if (rootRect.isEmpty()) {
            rootRect.set(0, 0, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }

        NameCandidate best = null;
        int screenCenterX = rootRect.centerX();
        int headerBottom = rootRect.top + Math.max(dp(150), rootRect.height() / 5);

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty() || r.top > headerBottom || r.bottom >= editorRect.top) continue;

            // İsim için yalnızca ekranda gerçekten yazan Text kullanılır. ContentDescription
            // SOYO'da TAG/rozet/erişilebilirlik etiketi olabildiği için ad kaynağı değildir.
            String raw = nodeText(node).trim();
            if (raw.isEmpty()) continue;
            String stripped = stripChatHeaderDecorations(raw);
            String name = normalizeNameCandidate(stripped);
            if (name == null) continue;

            int score = 18;
            int xDistance = Math.abs(r.centerX() - screenCenterX);
            score -= Math.min(10, xDistance / Math.max(1, dp(28)));
            if (r.centerX() > rootRect.left + rootRect.width() / 4
                    && r.centerX() < rootRect.right - rootRect.width() / 4) score += 8;
            if (r.top < rootRect.top + dp(120)) score += 5;
            if (!raw.equals(stripped)) score += 5; // "Murat VIP2" gibi başlık için güçlü sinyal
            if (name.indexOf(' ') < 0) score += 2;

            if (best == null || score > best.score) best = new NameCandidate(name, score);
        }
        recycleNodes(nodes);
        return best;
    }

    private String stripChatHeaderDecorations(String raw) {
        if (raw == null) return "";
        return cleanDecoratedName(raw);
    }

    private NameCandidate findBestName(AccessibilityNodeInfo root, AccessibilityNodeInfo editor) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        Rect editorRect = new Rect();
        editor.getBoundsInScreen(editorRect);

        NameCandidate best = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            String raw = nodeText(node).trim();
            String name = normalizeNameCandidate(raw);
            if (name == null) continue;

            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int score = 0;
            if (r.bottom <= editorRect.top) score += 8;
            int distance = Math.max(0, editorRect.top - r.bottom);
            score += Math.max(0, 8 - distance / Math.max(1, dp(60)));
            if (r.top < editorRect.top / 2) score += 3;
            if (name.indexOf(' ') < 0) score += 2;
            if (raw.equals(name)) score += 1;

            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().contains("TextView")) score += 1;

            if (best == null || score > best.score) best = new NameCandidate(name, score);
        }
        recycleNodes(nodes);
        return best;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, AccessibilityNodeInfo editor) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes, 0);
        Rect editorRect = new Rect();
        editor.getBoundsInScreen(editorRect);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String text = normalizedLower(nodeText(node) + " " + safe(node.getContentDescription()));
            String viewId = safe(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            boolean looksSend = text.contains("gönder") || text.contains("gonder") || text.contains("send")
                    || viewId.contains("send") || viewId.contains("gonder") || viewId.contains("gönder");
            boolean knownNonSend = text.contains("emoji") || text.contains("ifade")
                    || text.contains("sticker") || text.contains("çıkartma") || text.contains("cikartma")
                    || text.contains("mikrofon") || text.contains("microphone")
                    || text.contains("galeri") || text.contains("gallery") || text.contains("kamera");

            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty()) continue;

            int verticalDistance = Math.abs(r.centerY() - editorRect.centerY());
            boolean sameInputRow = verticalDistance <= dp(58);
            boolean rightOfEditor = r.centerX() > editorRect.centerX() && r.left >= editorRect.right - dp(20);
            boolean plausibleIcon = r.width() <= dp(110) && r.height() <= dp(110)
                    && r.width() >= dp(24) && r.height() >= dp(24);
            boolean geometryFallback = node.isClickable() && sameInputRow && rightOfEditor && plausibleIcon;

            if (knownNonSend && !looksSend) continue;
            if (!looksSend && !geometryFallback) continue;

            int score = 0;
            if (looksSend) score += 40;
            if (node.isClickable()) score += 8;
            if (sameInputRow) score += 12;
            if (rightOfEditor) score += 12;
            score -= Math.min(12, verticalDistance / Math.max(1, dp(6)));

            // Gönderilen ekran görüntüsünde emoji ile kâğıt uçak yan yana; kâğıt uçak en sağdaki
            // tıklanabilir simge. İçerik açıklaması yoksa sağdaki adayı tercih et.
            if (!looksSend && geometryFallback) {
                score += Math.min(18, Math.max(0, r.centerX() - editorRect.right) / Math.max(1, dp(8)));
            }

            if (score > bestScore) {
                recycleNode(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
        }
        recycleNodes(nodes);
        return best;
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        if (node == null || TextUtils.isEmpty(text)) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private void showApproval(String name, String message, boolean canSend) {
        if (windowManager == null) return;
        if (approvalView == null) createApprovalView();
        if (approvalText != null) approvalText.setText(name + " algılandı\n“" + message + "”");

        Button approve = approvalView.findViewWithTag("approve");
        if (approve != null) {
            approve.setEnabled(canSend);
            approve.setText(canSend ? "ONAYLA" : "GÖNDER BUTONU BULUNAMADI");
        }
        if (approvalView.getWindowToken() == null) {
            try {
                windowManager.addView(approvalView, overlayParams());
            } catch (Exception ignored) {
            }
        }
    }

    private void createApprovalView() {
        int pad = dp(14);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0xF2FFFFFF);
        card.setElevation(dp(8));

        approvalText = new TextView(this);
        approvalText.setTextSize(16);
        approvalText.setTextColor(0xFF111111);
        card.addView(approvalText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(10);
        card.addView(actions, actionsLp);

        Button cancel = new Button(this);
        cancel.setText("İPTAL / BU KİŞİYİ ATLA");
        cancel.setOnClickListener(v -> cancelPreparedMessage());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button approve = new Button(this);
        approve.setTag("approve");
        approve.setText("ONAYLA");
        approve.setOnClickListener(v -> approveSend());
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(52), 1.25f);
        approveLp.leftMargin = dp(8);
        actions.addView(approve, approveLp);

        approvalView = card;
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.x = 0;
        lp.y = dp(18);
        return lp;
    }

    private void approveSend() {
        AccessibilityNodeInfo button = pendingSendButton;
        String name = pendingName;
        if (button == null) {
            Toast.makeText(this, "Gönder butonu bulunamadı. Mesaj hazır; SOYO'dan gönder.", Toast.LENGTH_SHORT).show();
            markHandled(name);
            hideApproval();
            return;
        }

        boolean clicked = clickNodeOrParent(button);
        if (clicked) {
            markHandled(name);
            Toast.makeText(this, name + " için mesaj gönderildi.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Gönder düğmesine erişilemedi; mesaj kutuda hazır.", Toast.LENGTH_SHORT).show();
        }
        hideApproval();
    }

    private void cancelPreparedMessage() {
        String name = pendingName;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && pendingMessage != null) {
            AccessibilityNodeInfo editor = findBestEditor(root);
            if (editor != null && pendingMessage.equals(nodeText(editor))) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                recycleNode(editor);
            }
            root.recycle();
        }
        markHandled(name);
        hideApproval();
    }

    private void markHandled(String name) {
        if (!TextUtils.isEmpty(name)) handledRecommendationNames.add(nameKey(name));
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo clickable = findClickableParent(node);
        if (clickable == null) return false;
        boolean clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        recycleNode(clickable);
        return clicked;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        recycleNode(current);
        return null;
    }

    private void replacePendingSendButton(AccessibilityNodeInfo node) {
        recycleNode(pendingSendButton);
        pendingSendButton = node == null ? null : AccessibilityNodeInfo.obtain(node);
        recycleNode(node);
    }

    private void removeApprovalOverlayOnly() {
        if (approvalView != null && approvalView.getWindowToken() != null && windowManager != null) {
            try {
                windowManager.removeView(approvalView);
            } catch (Exception ignored) {
            }
        }
    }

    private void hideApproval() {
        removeApprovalOverlayOnly();
        recycleNode(pendingSendButton);
        pendingSendButton = null;
        pendingName = null;
        pendingMessage = null;
        unrecognizedChatSince = 0L;
    }

    private String buildMessage(String name) {
        String template = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TEMPLATE, DEFAULT_TEMPLATE);
        if (template == null || template.trim().isEmpty()) template = DEFAULT_TEMPLATE;
        int titleIndex = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_TITLE, 0);
        String title = titleIndex == 1 ? "Hanım" : titleIndex == 2 ? "" : "Bey";
        return template
                .replace("{isim}", name)
                .replace("{hitap}", title)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeNameCandidate(String raw) {
        if (raw == null) return null;
        String s = cleanDecoratedName(raw);
        if (s.length() < 2 || s.length() > 28) return null;
        String lower = normalizedLower(s);
        if (BLOCKED_WORDS.contains(lower) || isMetadataLabel(s)) return null;
        if (lower.contains("@") || lower.contains("http") || lower.matches(".*\\d.*")) return null;
        String rootLower = s.toLowerCase(Locale.ROOT).replace('ı', 'i');
        if (rootLower.contains("vip") || rootLower.contains("km") || rootLower.contains("elmas")) return null;
        // NFKC ile çözülen matematik/fancy harflerin yanında small-caps gibi Unicode
        // harflerini de kabul et. Böylece şekilli gerçek adlar TAG gibi etiketlere düşmez.
        if (!s.matches("[\\p{L}]+(?:[ '-][\\p{L}]+){0,2}")) return null;

        String[] parts = s.split("[ '-]");
        if (parts.length > 3 || parts[0].length() < 2) return null;
        return titleCase(s);
    }

    private boolean isMetadataLabel(String value) {
        if (TextUtils.isEmpty(value)) return true;
        String compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replaceAll("[\\s._:#-]+", "");
        return compact.matches("(?:tag|etiket|badge|rozet|vip\\d*|lv\\d*|level\\d*|seviye\\d*|id\\d*|uid\\d*)");
    }

    /**
     * SOYO adları matematiksel/fancy Unicode harfleri, emoji çerçeveleri ve VIP/level/tag
     * metinleriyle gösterebilir. Mesaj şablonuna girmeden önce bunları gerçek ada indirger.
     */
    private String cleanDecoratedName(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = foldStylizedLatin(s);
        s = s.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "");

        // Tek başına erişilebilirlik rozeti/etiketi hiçbir zaman isim değildir.
        if (isMetadataLabel(s)) return "";

        // Parantezli rozetler: Murat [VIP3], Murat (Lv.12), Murat {TAG}.
        s = s.replaceAll("\\s*[\\[({<][^\\])}>]{1,24}[\\])}>]", " ");
        // @etiket ve #etiket isim değildir.
        s = s.replaceAll("(?iu)(?:^|\\s)[@#][\\p{L}\\p{N}_.-]+", " ");
        // Adın sonuna eklenen rozet/seviye/kimlik metinleri ve devamını temizle.
        s = s.replaceAll("(?iu)\\s+(?:VIP|LV\\.?|LEVEL|SEVİYE|SEVIYE|ROZET|BADGE|TAG|ID)\\s*[:#-]?\\s*\\d*.*$", "");

        // Emoji, yıldız, taç ve dekoratif çerçeveleri at; harf, boşluk, kesme ve tire kalsın.
        s = s.replaceAll("[^\\p{L} '-]+", " ");
        s = removeCombiningMarks(s);
        return s.trim().replaceAll("\\s+", " ");
    }

    private String foldStylizedLatin(String value) {
        if (value == null || value.isEmpty()) return "";
        String from = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        String to   = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder out = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            int idx = from.indexOf(ch);
            if (idx >= 0) out.append(to.charAt(idx));
            else out.append(ch);
        }
        return out.toString();
    }

    private String removeCombiningMarks(String value) {
        // Türkçe harfleri koru; diğer harflerdeki yalnızca süs amaçlı aksanları sadeleştir.
        String protectedTurkish = "ÇĞİÖŞÜçğıöşüÂâÎîÛû";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (protectedTurkish.indexOf(codePoint) >= 0) {
                out.appendCodePoint(codePoint);
                continue;
            }
            String decomposed = Normalizer.normalize(
                    new String(Character.toChars(codePoint)), Normalizer.Form.NFD);
            for (int i = 0; i < decomposed.length(); i++) {
                char c = decomposed.charAt(i);
                if (Character.getType(c) != Character.NON_SPACING_MARK) out.append(c);
            }
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }

    private String titleCase(String raw) {
        String s = raw.trim().replaceAll("\\s+", " ");
        Locale tr = new Locale("tr", "TR");
        StringBuilder out = new StringBuilder();
        for (String part : s.split(" ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(tr));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(tr));
        }
        return out.toString();
    }

    private boolean isChatLabel(String s) {
        return "sohbet".equals(s) || "sohbete başla".equals(s) || "sohbete basla".equals(s);
    }

    private String nameKey(String name) {
        return normalizedLower(name);
    }

    private String normalizedLower(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(new Locale("tr", "TR"));
    }

    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 25 || out.size() > 500) return;
        out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    private String nodeText(AccessibilityNodeInfo node) {
        return node == null ? "" : safe(node.getText());
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) recycleNode(n);
        nodes.clear();
    }

    private void recycleTarget(RecommendationTarget target) {
        if (target != null) recycleNode(target.chatButton);
    }

    private void recycleNode(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
        automationEpoch++;
        autoSendScheduled = false;
        openingRecommendation = false;
        returningToRecommendations = false;
        mainHandler.removeCallbacksAndMessages(null);
        scanScheduled = false;
        scanScheduledFor = 0L;
        hideApproval();
        // AccessibilityService kesintisi servis kapandı demek değildir. Eski sürüm burada
        // watchdog'u kalıcı olarak öldürüyordu; servis olay almaya devam etse bile tam otomatik
        // akış bir süre sonra sessizce durabiliyordu. Kısa gecikmeyle watchdog'u yeniden kur.
        mainHandler.postDelayed(automationWatchdog, WATCHDOG_INTERVAL_MS);
        if (isFullAutoEnabled()) requestScan(200L);
    }

    @Override
    public void onDestroy() {
        automationEpoch++;
        autoSendScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        scanScheduled = false;
        scanScheduledFor = 0L;
        hideApproval();
        super.onDestroy();
    }

    private static class NameCandidate {
        final String name;
        final int score;

        NameCandidate(String name, int score) {
            this.name = name;
            this.score = score;
        }
    }

    private static class RecommendationTarget {
        final String name;
        final AccessibilityNodeInfo chatButton;
        final Rect chatRect;
        final int score;
        final boolean vip;

        RecommendationTarget(String name, AccessibilityNodeInfo chatButton, Rect chatRect, int score, boolean vip) {
            this.name = name;
            this.chatButton = chatButton;
            this.chatRect = chatRect;
            this.score = score;
            this.vip = vip;
        }
    }
}
