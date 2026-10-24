package dev.billy.bravetv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brave TV — Native Android TV YouTube & Web experience.
 * - Uses YouTube TV's native D-pad interface (https://www.youtube.com/tv) with Smart-TV User-Agent
 *   so your TV remote works 100% natively (Up/Down/Left/Right/OK/Back — no mouse pointer needed!).
 * - Uses Brave/uBlock-style JSON.parse & network interception to strip `adPlacements`, `playerAds`,
 *   and `adSlots` before the player ever sees an ad, plus instant video ad skip fallback.
 * - Press MENU (or long-press BACK) anytime to switch between YouTube TV and full Web Browser mode.
 */
public final class MainActivity extends Activity {
    // Smart TV User-Agent unlocks the real D-pad YouTube TV app interface at youtube.com/tv
    private static final String SMART_TV_UA =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 TV Safari/537.36";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Brave/131";
    private static final String YT_TV_URL = "https://www.youtube.com/tv";

    private SharedPreferences prefs;
    private float unit = 1f;
    private float tvCssWidth = 960f;
    private FrameLayout root;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customVideoCallback;
    private ProgressBar progressBar;

    // Top bar overlay (opened with MENU or from web mode)
    private FrameLayout topBarCard;
    private LinearLayout topBarRow1, topBarRow2;
    private EditText urlInput;
    private TextView shieldBtn, modeBtn;
    private boolean barVisible = false;

    // Cursor only used when browsing normal desktop websites (automatically hidden on YouTube TV!)
    private CursorView cursorView;
    private float cursorX, cursorY;
    private boolean shieldsEnabled = true;
    private final AtomicInteger blockedCount = new AtomicInteger(0);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBackPress = 0;

    private static final String[][] QUICK_SITES = {
        {"▶ YouTube TV", "https://www.youtube.com/tv", "#FF0033"},
        {"🦁 Brave Search", "https://search.brave.com", "#FB542B"},
        {"HiAnime", "https://hianime.to", "#FFBADE"},
        {"Twitch", "https://www.twitch.tv", "#9146FF"},
        {"Reddit", "https://www.reddit.com", "#FF4500"},
        {"GitHub", "https://github.com/bil-ly", "#2B3137"}
    };

    private static final String[] AD_DOMAINS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adnxs.com", "adsrvr.org",
        "adform.net", "adsystem.com", "amazon-adsystem.com", "taboola.com", "outbrain.com",
        "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "adsterra.com", "hilltopads.net", "clickadu.com", "bidvertiser.com",
        "revcontent.com", "mgid.com", "zedo.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "2mdn.net", "admob.com", "adroll.com"
    };

    // Brave / uBlock style ad-stripping script injected into YouTube TV & web pages
    private static final String BRAVE_YT_SHIELD_JS =
        "(function(){" +
        "if(window.__braveYtShieldInstalled)return;" +
        "window.__braveYtShieldInstalled=true;" +
        "function stripAds(obj){" +
        "  if(!obj||typeof obj!=='object')return obj;" +
        "  delete obj.adPlacements;delete obj.playerAds;delete obj.adSlots;delete obj.adBreakHeartbeatParams;" +
        "  if(obj.playerResponse){delete obj.playerResponse.adPlacements;delete obj.playerResponse.playerAds;delete obj.playerResponse.adSlots;}" +
        "  return obj;" +
        "}" +
        "const origParse=JSON.parse;" +
        "JSON.parse=function(){return stripAds(origParse.apply(this,arguments));};" +
        "if(window.Response&&Response.prototype.json){" +
        "  const origJson=Response.prototype.json;" +
        "  Response.prototype.json=function(){return origJson.apply(this,arguments).then(stripAds);};" +
        "}" +
        "setInterval(function(){" +
        "  var skip=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,[class*=\"skip-button\"]');" +
        "  if(skip){try{skip.click();}catch(e){}}" +
        "  var adPlayer=document.querySelector('.ad-showing video, .ad-interrupting video');" +
        "  if(adPlayer&&!isNaN(adPlayer.duration)&&adPlayer.duration>0){adPlayer.muted=true;adPlayer.currentTime=adPlayer.duration;}" +
        "},250);" +
        "var st=document.createElement('style');" +
        "st.textContent='ytlr-ad-placement-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,.ytp-ad-overlay-container,.ytp-ad-message-container,iframe[src*=\"doubleclick\"]{display:none!important;} video::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-panel,video::-webkit-media-controls-overlay-play-button{display:none!important;opacity:0!important;}';" +
        "if(document.documentElement)document.documentElement.appendChild(st);" +
        "})();";

    private int px(float v) { return Math.round(v * unit); }

    private boolean isYouTubeTvMode() {
        String u = webView != null ? webView.getUrl() : "";
        return u != null && (u.contains("youtube.com/tv") || u.contains("youtube.com/leanback"));
    }

    private GradientDrawable shape(int color, float radius, int strokeColor, float strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(px(radius));
        if (strokeW > 0) gd.setStroke(Math.max(1, px(strokeW)), strokeColor);
        return gd;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("brave_tv", MODE_PRIVATE);
        shieldsEnabled = prefs.getBoolean("shields", true);

        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        unit = m.widthPixels / 960f;
        tvCssWidth = m.widthPixels / (m.density > 0 ? m.density : 2f);
        cursorX = m.widthPixels / 2f;
        cursorY = m.heightPixels / 2f;

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D13);

        createWebView();
        createProgressBar();
        createCursor();
        createTopBar();

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        String initialUrl = getIntent() != null && getIntent().getDataString() != null
            ? getIntent().getDataString()
            : YT_TV_URL;
        loadTarget(initialUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getDataString() != null) {
            loadTarget(intent.getDataString());
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B0D13);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(SMART_TV_UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setTextZoom(100);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if (shieldsEnabled && req != null && req.getUrl() != null) {
                    String host = req.getUrl().getHost();
                    String full = req.getUrl().toString().toLowerCase(Locale.US);
                    if (host != null && isBlockedUrl(host.toLowerCase(Locale.US), full)) {
                        int count = blockedCount.incrementAndGet();
                        if (count % 2 == 1) handler.post(MainActivity.this::updateShieldPill);
                        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                            Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                            new ByteArrayInputStream(new byte[0]));
                    }
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (req == null || req.getUrl() == null) return false;
                String url = req.getUrl().toString();
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                urlInput.setText(url);
                syncModeForUrl(url);
                if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                handler.postDelayed(() -> {
                    if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                    injectViewportFit(url);
                }, 150);
                updateShieldPill();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncModeForUrl(url);
                if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                injectViewportFit(url);
                updateShieldPill();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                // Return a 1x1 transparent/black bitmap so Android WebView never shows the giant ugly grey play icon before a video starts
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return false; // Block all popup windows
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customVideoView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customVideoView = view;
                customVideoCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreenContainer.setVisibility(View.VISIBLE);
                cursorView.setVisibility(View.GONE);
                hideTopBar();
            }

            @Override
            public void onHideCustomView() {
                if (customVideoView == null) return;
                fullscreenContainer.removeView(customVideoView);
                fullscreenContainer.setVisibility(View.GONE);
                customVideoView = null;
                if (customVideoCallback != null) customVideoCallback.onCustomViewHidden();
                syncModeForUrl(webView.getUrl());
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void syncModeForUrl(String url) {
        boolean ytTv = url != null && (url.contains("youtube.com/tv") || url.contains("youtube.com/leanback"));
        if (cursorView != null) {
            cursorView.setVisibility(ytTv ? View.GONE : View.VISIBLE);
        }
        if (modeBtn != null) {
            modeBtn.setText(ytTv ? "Mode: Native TV" : "Mode: Web Pointer");
        }
        if (ytTv && !barVisible) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }
    }

    private boolean isBlockedUrl(String host, String fullUrl) {
        for (String domain : AD_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return fullUrl.contains("/pagead/") || fullUrl.contains("/ptracking") ||
               fullUrl.contains("/api/stats/ads") || fullUrl.contains("get_midroll_info") ||
               fullUrl.contains("/adserver") || fullUrl.contains("popunder");
    }

    private void injectViewportFit(String url) {
        if (webView == null || url == null) return;
        // YouTube TV native interface handles its own 16:9 resolution natively
        if (url.contains("youtube.com/tv")) return;
        float scale = tvCssWidth / 1440f;
        String scaleStr = String.format(Locale.US, "%.4f", scale);
        String js = "(function(){" +
            "var vp=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!vp){vp=document.createElement('meta');vp.name='viewport';document.head&&document.head.appendChild(vp);}" +
            "vp.setAttribute('content','width=1440, initial-scale=" + scaleStr + ", minimum-scale=" + scaleStr + ", maximum-scale=" + scaleStr + ", user-scalable=no');" +
            "var fit=document.getElementById('brave-tv-fit');" +
            "if(!fit){fit=document.createElement('style');fit.id='brave-tv-fit';document.head&&document.head.appendChild(fit);}" +
            "fit.textContent='html,body{max-width:100vw!important;overflow-x:hidden!important;}';" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void createProgressBar() {
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(3));
        lp.gravity = Gravity.TOP;
        root.addView(progressBar, lp);
    }

    private void createCursor() {
        cursorView = new CursorView(this);
        cursorView.setVisibility(View.GONE);
        root.addView(cursorView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void createTopBar() {
        topBarCard = new FrameLayout(this);
        topBarCard.setBackground(shape(0xF212151E, 20, 0x33FFFFFF, 1));
        topBarCard.setElevation(px(30));
        topBarCard.setPadding(px(16), px(12), px(16), px(12));

        LinearLayout vBox = new LinearLayout(this);
        vBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        topBarRow1 = row1;
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        View ytBtn = barButton("▶ YouTube TV", 0xFFFF0033, () -> { loadTarget(YT_TV_URL); hideTopBar(); });
        View backBtn = barButton("←", 0x26FFFFFF, () -> { if (webView.canGoBack()) webView.goBack(); hideTopBar(); });
        View reloadBtn = barButton("↻", 0x26FFFFFF, () -> { webView.reload(); hideTopBar(); });

        row1.addView(ytBtn, marginRight(px(106), px(36), 6));
        row1.addView(backBtn, marginRight(px(40), px(36), 6));
        row1.addView(reloadBtn, marginRight(px(40), px(36), 8));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Search Brave or enter website URL…");
        urlInput.setHintTextColor(0x88FFFFFF);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(14));
        urlInput.setPadding(px(14), 0, px(14), 0);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        Runnable paintUrl = () -> urlInput.setBackground(
            shape(0xFF1C2130, 12, urlInput.hasFocus() ? 0xFFFB542B : 0x33FFFFFF, urlInput.hasFocus() ? 2f : 1f));
        paintUrl.run();
        urlInput.setOnFocusChangeListener((v, f) -> {
            paintUrl.run();
            if (f) urlInput.selectAll();
        });
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            String q = urlInput.getText().toString().trim();
            if (!q.isEmpty()) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
                loadTarget(q);
                hideTopBar();
            }
            return true;
        });
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, px(36), 1f);
        urlLp.rightMargin = px(8);
        row1.addView(urlInput, urlLp);

        shieldBtn = barButton("", 0xFF1E293B, () -> {
            shieldsEnabled = !shieldsEnabled;
            prefs.edit().putBoolean("shields", shieldsEnabled).apply();
            updateShieldPill();
            webView.reload();
        });
        modeBtn = barButton("Mode: Native TV", 0x26FFFFFF, this::hideTopBar);

        row1.addView(shieldBtn, marginRight(px(116), px(36), 6));
        row1.addView(modeBtn, new LinearLayout.LayoutParams(px(124), px(36)));
        vBox.addView(row1, new LinearLayout.LayoutParams(-1, px(40)));

        LinearLayout row2 = new LinearLayout(this);
        topBarRow2 = row2;
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, px(34));
        r2lp.topMargin = px(8);

        for (String[] site : QUICK_SITES) {
            final String targetUrl = site[1];
            TextView pill = barButton(site[0], 0x1FFFFFFF, () -> {
                loadTarget(targetUrl);
                hideTopBar();
            });
            pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12.5f));
            row2.addView(pill, marginRight(px(108), px(30), 6));
        }

        TextView hint = new TextView(this);
        hint.setText("Hold BACK or press MENU anytime for this bar");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11.5f));
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row2.addView(hint, new LinearLayout.LayoutParams(0, -1, 1f));

        vBox.addView(row2, r2lp);
        topBarCard.addView(vBox, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(px(916), -2);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = px(14);
        topBarCard.setVisibility(View.GONE);
        root.addView(topBarCard, cardLp);
        updateShieldPill();
    }

    private LinearLayout.LayoutParams marginRight(int w, int h, float rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = px(rightDp);
        return lp;
    }

    private TextView barButton(String text, int baseColor, Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        Runnable paint = () -> {
            boolean f = b.hasFocus();
            b.setBackground(shape(f ? Color.WHITE : baseColor, 10, f ? 0xFFFB542B : 0x22FFFFFF, f ? 1.5f : 1f));
            b.setTextColor(f ? 0xFF111827 : Color.WHITE);
        };
        paint.run();
        b.setOnFocusChangeListener((v, f) -> paint.run());
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void updateShieldPill() {
        if (shieldBtn == null) return;
        shieldBtn.setText(shieldsEnabled ? ("🛡️ Shields · " + blockedCount.get()) : "🛡️ Shields OFF");
    }

    private void showTopBar() {
        barVisible = true;
        topBarCard.setVisibility(View.VISIBLE);
        topBarCard.setAlpha(0f);
        topBarCard.setTranslationY(-px(12));
        topBarCard.animate().alpha(1f).translationY(0).setDuration(180).start();
        topBarRow1.getChildAt(0).requestFocus();
    }

    private void hideTopBar() {
        barVisible = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && urlInput != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        topBarCard.setVisibility(View.GONE);
        if (isYouTubeTvMode()) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }
    }

    private void loadTarget(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty() || q.equalsIgnoreCase("brave://home") || q.equalsIgnoreCase("youtube")) {
            q = YT_TV_URL;
        }
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) {
            url = q;
        } else if (!q.contains(" ") && q.contains(".")) {
            url = "https://" + q;
        } else {
            url = "https://search.brave.com/search?q=" + Uri.encode(q);
        }
        boolean ytTv = url.contains("youtube.com/tv") || url.contains("youtube.com/leanback");
        webView.getSettings().setUserAgentString(ytTv ? SMART_TV_UA : DESKTOP_UA);
        syncModeForUrl(url);
        webView.loadUrl(url);
    }

    private void clickAtCursor() {
        cursorView.pulse();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 45, MotionEvent.ACTION_UP, cursorX, cursorY, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        int action = event.getAction();

        // Toggle top bar with MENU button anytime, or long-press BACK
        if (key == KeyEvent.KEYCODE_MENU || (key == KeyEvent.KEYCODE_BACK && event.isLongPress())) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (barVisible) hideTopBar(); else showTopBar();
            }
            return true;
        }

        // When Top Bar is open, navigate the Top Bar with D-pad
        if (barVisible) {
            if (key == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                hideTopBar();
                return true;
            }
            if (action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                View focused = getCurrentFocus();
                if (focused != null && focused.getParent() == topBarRow2) {
                    hideTopBar();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        // In Native YouTube TV Mode: pass ALL D-pad & OK keys straight to the YouTube TV app UI!
        if (isYouTubeTvMode()) {
            if (key == KeyEvent.KEYCODE_BACK) {
                if (action == KeyEvent.ACTION_DOWN) {
                    long now = SystemClock.uptimeMillis();
                    // Double-tap Back within 400ms opens the Brave TV Top Bar
                    if (now - lastBackPress < 400) {
                        showTopBar();
                        return true;
                    }
                    lastBackPress = now;
                    // Pass Escape key to YouTube TV so it navigates back inside the YouTube TV UI
                    webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                    webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // In Web Browser Mode: BACK opens Top Bar
        if (key == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (customVideoView != null) {
                    webView.getWebChromeClient().onHideCustomView();
                } else {
                    showTopBar();
                }
            }
            return true;
        }

        if (action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);

        int repeat = event.getRepeatCount();
        float speed = px(repeat > 8 ? 38 : repeat > 3 ? 24 : 14);
        int w = Math.max(1, root.getWidth());
        int h = Math.max(1, root.getHeight());

        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (cursorY <= px(12)) { showTopBar(); return true; }
            cursorY = Math.max(px(6), cursorY - speed);
            if (cursorY < h * 0.14f) webView.scrollBy(0, -px(95));
            cursorView.invalidate();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            cursorY = Math.min(h - px(6), cursorY + speed);
            if (cursorY > h * 0.86f) webView.scrollBy(0, px(95));
            cursorView.invalidate();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            cursorX = Math.max(px(6), cursorX - speed);
            if (cursorX < w * 0.08f) webView.scrollBy(-px(80), 0);
            cursorView.invalidate();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            cursorX = Math.min(w - px(6), cursorX + speed);
            if (cursorX > w * 0.92f) webView.scrollBy(px(80), 0);
            cursorView.invalidate();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            clickAtCursor();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private final class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float scale = 1f;

        CursorView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        void pulse() {
            scale = 0.72f;
            invalidate();
            handler.postDelayed(() -> { scale = 1f; invalidate(); }, 110);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = px(13) * scale;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x55000000);
            c.drawCircle(cursorX + px(1), cursorY + px(2), r + px(3), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(2.8f));
            paint.setColor(0xFFFB542B);
            c.drawCircle(cursorX, cursorY, r, paint);
            paint.setStrokeWidth(px(1.5f));
            paint.setColor(Color.WHITE);
            c.drawCircle(cursorX, cursorY, r - px(2.2f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFB542B);
            c.drawCircle(cursorX, cursorY, px(2.8f), paint);
        }
    }
}
