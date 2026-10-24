package dev.billy.bravetv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brave TV — Native Android TV YouTube, Live Soccer/Sports Hub & Web experience.
 * - Uses YouTube TV's native D-pad interface (https://www.youtube.com/tv) with Smart-TV User-Agent.
 * - Integrates Live Sports directly from https://t.me/s/totalsporteki:
 *   scrapes live match cards + stream servers in background and launches streams in a 16:9 Cinema
 *   Isolator with aggressive popup/ad-script/overlay killing.
 * - Press MENU (or long-press BACK) anytime to switch between YouTube TV, Live Sports, and Web mode.
 */
public final class MainActivity extends Activity {
    private static final String SMART_TV_UA =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 TV Safari/537.36";
    private static final String DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Brave/131";
    private static final String YT_TV_URL = "https://www.youtube.com/tv";
    private static final String TELEGRAM_CHANNEL_URL = "https://t.me/s/totalsporteki";

    private SharedPreferences prefs;
    private float unit = 1f;
    private float tvCssWidth = 960f;
    private FrameLayout root;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customVideoCallback;
    private ProgressBar progressBar;

    // Top bar overlay (opened with MENU or long-press BACK)
    private FrameLayout topBarCard;
    private LinearLayout topBarRow1, topBarRow2;
    private EditText urlInput;
    private TextView shieldBtn, modeBtn;
    private boolean barVisible = false;

    // Native Live Sports Hub Overlay (D-pad driven, scrapes t.me/s/totalsporteki & sportslinks.is)
    private FrameLayout sportsOverlay;
    private LinearLayout matchesListContainer;
    private LinearLayout serversListContainer;
    private TextView sportsStatusText;
    private TextView serversHeaderText;
    private boolean sportsVisible = false;
    private boolean inSportsStreamMode = false;
    private volatile String activeStreamHost = "";
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();

    // Cursor only used when browsing normal desktop websites (hidden on YouTube TV)
    private CursorView cursorView;
    private float cursorX, cursorY;
    private boolean shieldsEnabled = true;
    private final AtomicInteger blockedCount = new AtomicInteger(0);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBackPress = 0;

    private static final class MatchItem {
        final String competition;
        final String title;
        final String timeInfo;
        final String link;
        MatchItem(String competition, String title, String timeInfo, String link) {
            this.competition = competition;
            this.title = title;
            this.timeInfo = timeInfo;
            this.link = link;
        }
    }

    private static final class ServerItem {
        final String name;
        final String url;
        ServerItem(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final String[][] QUICK_SITES = {
        {"▶ YouTube TV", "https://www.youtube.com/tv", "#FF0033"},
        {"⚽ Live Soccer", "brave://sports", "#10B981"},
        {"🦁 Brave Search", "https://search.brave.com", "#FB542B"},
        {"HiAnime", "https://hianime.to", "#FFBADE"},
        {"Twitch", "https://www.twitch.tv", "#9146FF"},
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
        "moatads.com", "2mdn.net", "admob.com", "adroll.com",
        // Sports stream ad/popup networks found on totalsportek / sportslinks servers
        "czernik.org", "capitalhospitals.org", "thirstacock.com", "livelog.site",
        "arcadiablankdenying.com", "histats.com", "acscdn.com", "usrpubtrk.com",
        "onclickalgo.com", "onclckmn.com", "wpadmngr.com", "tsyndicate.com",
        "bidgear.com", "galaksion.com", "pushame.com", "pushwhy.com", "rtmark.net",
        "adcash.com", "alfadart.com", "bet365.com", "1xbet.com", "stake.com",
        "xadsmart.com", "ubogicetmixgdl.com", "vexlcier.com", "cloudfront.net/kt/"
    };

    private static final String[] ALLOWED_STREAM_CDNS = {
        "jsdelivr.net", "cdnjs.cloudflare.com", "code.jquery.com", "unpkg.com",
        "googleapis.com", "gstatic.com", "wp.com", "vimeocdn.com", "jwpsrv.com",
        "jwpcdn.com", "swarmcloud.net", "web3-lab.com", "youtube.com",
        "youtube-nocookie.com", "ytimg.com", "googlevideo.com", "twitch.tv",
        "ttvnw.net", "akamaized.net", "fastly.net", "cloudflare.com"
    };

    // Brave / uBlock style ad-stripping + sports stream popup/overlay killer + 16:9 Cinema Isolator
    private static final String BRAVE_YT_SHIELD_JS =
        "(function(){" +
        "if(window.__braveYtShieldInstalled)return;" +
        "window.__braveYtShieldInstalled=true;" +
        // 1. Neutralize popup/redirect functions used by sports streaming sites
        "window.open=function(){return null;};" +
        "window.alert=function(){};window.confirm=function(){return false;};window.prompt=function(){return null;};" +
        "window.aclib={runPop:function(){},runBanner:function(){},runAutoTag:function(){},runInPagePush:function(){}};" +
        "try{Object.defineProperty(window,'atOptions',{get:function(){return {};},set:function(){}});}catch(e){}" +
        // 2. Strip YouTube JSON ad payloads
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
        // 3. Periodic YouTube ad skip + Sports stream invisible click-hijack overlay killer
        "setInterval(function(){" +
        "  var skip=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,[class*=\"skip-button\"]');" +
        "  if(skip){try{skip.click();}catch(e){}}" +
        "  var adPlayer=document.querySelector('.ad-showing video, .ad-interrupting video');" +
        "  if(adPlayer&&!isNaN(adPlayer.duration)&&adPlayer.duration>0){adPlayer.muted=true;adPlayer.currentTime=adPlayer.duration;}" +
        "  if(!location.hostname.includes('youtube.com')){" +
        "    var els=document.querySelectorAll('div,a,ins,aside');" +
        "    for(var i=0;i<els.length;i++){" +
        "      var el=els[i];" +
        "      var st=window.getComputedStyle(el);" +
        "      var z=parseInt(st.zIndex,10)||0;" +
        "      if((st.position==='fixed'||st.position==='absolute')&&z>900&&!el.querySelector('video,iframe')){" +
        "        el.remove();" +
        "      }" +
        "    }" +
        "  }" +
        "},300);" +
        "var st=document.createElement('style');" +
        "st.textContent='ytlr-ad-placement-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,.ytp-ad-overlay-container,.ytp-ad-message-container,.ad-header,.ad-box,.top-nav,.footer,[class*=\"ad-banner\"],[id*=\"ad-container\"],iframe[src*=\"doubleclick\"],iframe[src*=\"histats\"],iframe[src*=\"czernik\"]{display:none!important;} video::-webkit-media-controls,video::-webkit-media-controls-enclosure,video::-webkit-media-controls-panel,video::-webkit-media-controls-overlay-play-button{display:none!important;opacity:0!important;}';" +
        "if(document.documentElement)document.documentElement.appendChild(st);" +
        "})();";

    // When watching a sports stream server, isolate the video/player container into full-screen 16:9 dark cinema
    private static final String SPORTS_CINEMA_ISOLATOR_JS =
        "(function(){" +
        "if(location.hostname.includes('youtube.com'))return;" +
        "var st=document.getElementById('brave-sports-cinema');" +
        "if(!st){st=document.createElement('style');st.id='brave-sports-cinema';document.head&&document.head.appendChild(st);}" +
        "st.textContent='html,body{background:#000!important;color:#fff!important;margin:0!important;padding:0!important;overflow:hidden!important;} .top-nav,.ad-header,.ad-box,.footer,header,nav,footer,aside,.chat,.sidebar,[class*=\"ad-\"],[id*=\"ad-\"],[id*=\"banner\"],[class*=\"banner\"],ins{display:none!important;opacity:0!important;pointer-events:none!important;} .player-container,.player-wrap,#pw,#stage,#player,.player{max-width:100vw!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;position:fixed!important;top:0!important;left:0!important;z-index:999999!important;background:#000!important;} .player iframe,#player iframe,.player-wrap iframe{width:100vw!important;height:100vh!important;border:0!important;border-radius:0!important;}';" +
        "function autoStartAndUnmute(){" +
        "  try{if(typeof WSUnmute==='function')WSUnmute();}catch(e){}" +
        "  var unmuteBtns=document.querySelectorAll('#UnMutePlayer,.unmute,.alxskin__mute,.alxskin__play,[aria-label*=\"Unmute\"]');" +
        "  for(var i=0;i<unmuteBtns.length;i++){try{unmuteBtns[i].click();}catch(e){}}" +
        "  var v=document.querySelector('video');" +
        "  if(v){v.muted=false;v.volume=1.0;try{v.play();}catch(e){}}" +
        "  var bad=document.querySelectorAll('div,a,ins,iframe');" +
        "  for(var j=0;j<bad.length;j++){" +
        "    var el=bad[j];" +
        "    if(el.tagName==='IFRAME'){" +
        "      var r=el.getBoundingClientRect();" +
        "      if((r.width>0&&r.width<350)||(r.height>0&&r.height<180)){el.remove();continue;}" +
        "    } else {" +
        "      var cs=window.getComputedStyle(el);" +
        "      var z=parseInt(cs.zIndex,10)||0;" +
        "      if((cs.position==='fixed'||cs.position==='absolute')&&z>50&&!el.querySelector('video,iframe')&&el.id!=='player'&&el.id!=='pw'&&!el.classList.contains('player')&&!el.classList.contains('player-wrap')){" +
        "        el.remove();" +
        "      }" +
        "    }" +
        "  }" +
        "}" +
        "autoStartAndUnmute();" +
        "if(!window.__braveSportsTimer){window.__braveSportsTimer=setInterval(autoStartAndUnmute,500);}" +
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
        createSportsOverlay();
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
                String host = req.getUrl().getHost() != null ? req.getUrl().getHost().toLowerCase(Locale.US) : "";
                // Block non-HTTP intents
                if (!(url.startsWith("http://") || url.startsWith("https://"))) return true;
                // In Sports Stream Mode, NEVER allow top-frame ad redirects away from the stream server!
                if (inSportsStreamMode && req.isForMainFrame()) {
                    if (!activeStreamHost.isEmpty() && !host.equals(activeStreamHost) && !host.endsWith("." + activeStreamHost)) {
                        blockedCount.incrementAndGet();
                        updateShieldPill();
                        return true;
                    }
                }
                if (shieldsEnabled && isBlockedUrl(host, url.toLowerCase(Locale.US))) {
                    blockedCount.incrementAndGet();
                    updateShieldPill();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                urlInput.setText(url);
                syncModeForUrl(url);
                if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                handler.postDelayed(() -> {
                    if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                    if (inSportsStreamMode) view.evaluateJavascript(SPORTS_CINEMA_ISOLATOR_JS, null);
                    injectViewportFit(url);
                }, 150);
                updateShieldPill();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                syncModeForUrl(url);
                if (shieldsEnabled) view.evaluateJavascript(BRAVE_YT_SHIELD_JS, null);
                if (inSportsStreamMode) {
                    view.evaluateJavascript(SPORTS_CINEMA_ISOLATOR_JS, null);
                    handler.postDelayed(() -> view.evaluateJavascript(SPORTS_CINEMA_ISOLATOR_JS, null), 1200);
                }
                injectViewportFit(url);
                updateShieldPill();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public Bitmap getDefaultVideoPoster() {
                // Return a 1x1 transparent bitmap so Android WebView never flashes the giant grey play icon
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
                // Block all popup windows & tab-unders unconditionally
                blockedCount.incrementAndGet();
                updateShieldPill();
                return false;
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
            cursorView.setVisibility((ytTv || sportsVisible) ? View.GONE : View.VISIBLE);
        }
        if (modeBtn != null) {
            modeBtn.setText(ytTv ? "Mode: Native TV" : (inSportsStreamMode ? "Mode: Sports Cinema" : "Mode: Web Pointer"));
        }
        if (ytTv && !barVisible && !sportsVisible) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }
    }

    private boolean isBlockedUrl(String host, String fullUrl) {
        for (String domain : AD_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        if (fullUrl.contains("/pagead/") || fullUrl.contains("/ptracking") ||
            fullUrl.contains("/api/stats/ads") || fullUrl.contains("get_midroll_info") ||
            fullUrl.contains("/adserver") || fullUrl.contains("popunder") ||
            fullUrl.contains("histats.com") || fullUrl.contains("aclib") ||
            fullUrl.contains("ytr5541122hhgfdd.js") || fullUrl.contains("jscroll.min.js") ||
            fullUrl.contains("ematrix.min.css") || fullUrl.contains("nmatrix.min.css")) {
            return true;
        }
        // Strict Zero-Ad Lock when watching a Sports Stream Server:
        // Block all third-party random ad-script fetches (e.g. domain.com/22/<32-char-md5>)
        if (inSportsStreamMode) {
            // Always allow HLS video playlists, segments, and player iframes
            if (fullUrl.contains(".m3u8") || fullUrl.contains(".ts") || fullUrl.contains(".mp4") ||
                fullUrl.contains(".m4s") || fullUrl.contains(".aac") || fullUrl.contains("embed") ||
                fullUrl.contains("player") || fullUrl.contains("stream") || fullUrl.contains("hls") ||
                fullUrl.contains("ch-") || fullUrl.contains("ch3") || fullUrl.contains("watch")) {
                return false;
            }
            // Block Adsterra / PopAds 32-char hex script paths on rotating domains
            if (fullUrl.matches(".*\\/[0-9a-f]{32}(\\.js)?(\\?.*)?$")) {
                return true;
            }
            // If it's a third-party JS script not from the active stream host or known player CDNs, block it!
            if (!activeStreamHost.isEmpty() && !host.equals(activeStreamHost) && !host.endsWith("." + activeStreamHost)) {
                boolean allowedCdn = false;
                for (String cdn : ALLOWED_STREAM_CDNS) {
                    if (host.equals(cdn) || host.endsWith("." + cdn)) {
                        allowedCdn = true;
                        break;
                    }
                }
                if (!allowedCdn && (fullUrl.endsWith(".js") || fullUrl.contains(".js?") || fullUrl.contains("/tracker"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void injectViewportFit(String url) {
        if (webView == null || url == null) return;
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

    // =========================================================================
    // LIVE SOCCER / SPORTS HUB OVERLAY (Scrapes t.me/s/totalsporteki + servers)
    // =========================================================================
    private void createSportsOverlay() {
        sportsOverlay = new FrameLayout(this);
        sportsOverlay.setBackgroundColor(0xF40B0E16);
        sportsOverlay.setVisibility(View.GONE);

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setPadding(px(36), px(28), px(36), px(28));

        // Header Row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText("⚽ TOTALSPORTEK LIVE");
        badge.setTextColor(0xFF10B981);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(18));
        badge.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        header.addView(badge, marginRight(-2, -2, 16));

        sportsStatusText = new TextView(this);
        sportsStatusText.setText("Synced with t.me/totalsporteki · Zero Ads & Zero Popups");
        sportsStatusText.setTextColor(0xAAFFFFFF);
        sportsStatusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
        header.addView(sportsStatusText, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView refreshBtn = barButton("↻ Refresh Matches", 0xFF1E293B, this::fetchTelegramMatches);
        header.addView(refreshBtn, marginRight(px(140), px(34), 10));

        TextView closeBtn = barButton("✕ Back to Video", 0x26FFFFFF, this::hideSportsOverlay);
        header.addView(closeBtn, new LinearLayout.LayoutParams(px(130), px(34)));

        mainCol.addView(header, new LinearLayout.LayoutParams(-1, px(44)));

        // Two-Column Body: Left = Live Matches from Telegram, Right = Clean Stream Servers
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        colLp.topMargin = px(16);

        // Left Column (Matches)
        LinearLayout leftBox = new LinearLayout(this);
        leftBox.setOrientation(LinearLayout.VERTICAL);
        leftBox.setBackground(shape(0xFF131824, 16, 0x22FFFFFF, 1));
        leftBox.setPadding(px(16), px(14), px(16), px(14));

        TextView leftTitle = new TextView(this);
        leftTitle.setText("1. SELECT LIVE MATCH (FROM TELEGRAM)");
        leftTitle.setTextColor(0x99FFFFFF);
        leftTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11.5f));
        leftTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        leftBox.addView(leftTitle, new LinearLayout.LayoutParams(-1, px(22)));

        ScrollView matchScroll = new ScrollView(this);
        matchScroll.setVerticalScrollBarEnabled(false);
        matchesListContainer = new LinearLayout(this);
        matchesListContainer.setOrientation(LinearLayout.VERTICAL);
        matchScroll.addView(matchesListContainer, new FrameLayout.LayoutParams(-1, -2));
        leftBox.addView(matchScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -1, 1.05f);
        leftLp.rightMargin = px(16);
        columns.addView(leftBox, leftLp);

        // Right Column (Stream Servers)
        LinearLayout rightBox = new LinearLayout(this);
        rightBox.setOrientation(LinearLayout.VERTICAL);
        rightBox.setBackground(shape(0xFF131824, 16, 0x22FFFFFF, 1));
        rightBox.setPadding(px(16), px(14), px(16), px(14));

        serversHeaderText = new TextView(this);
        serversHeaderText.setText("2. SELECT AD-FREE STREAM SERVER");
        serversHeaderText.setTextColor(0x99FFFFFF);
        serversHeaderText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11.5f));
        serversHeaderText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        rightBox.addView(serversHeaderText, new LinearLayout.LayoutParams(-1, px(22)));

        ScrollView serverScroll = new ScrollView(this);
        serverScroll.setVerticalScrollBarEnabled(false);
        serversListContainer = new LinearLayout(this);
        serversListContainer.setOrientation(LinearLayout.VERTICAL);
        serverScroll.addView(serversListContainer, new FrameLayout.LayoutParams(-1, -2));
        rightBox.addView(serverScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        columns.addView(rightBox, new LinearLayout.LayoutParams(0, -1, 1.15f));
        mainCol.addView(columns, colLp);

        sportsOverlay.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));
        root.addView(sportsOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showSportsOverlay() {
        hideTopBar();
        sportsVisible = true;
        sportsOverlay.setVisibility(View.VISIBLE);
        cursorView.setVisibility(View.GONE);
        if (matchesListContainer.getChildCount() == 0) {
            fetchTelegramMatches();
        } else {
            matchesListContainer.getChildAt(0).requestFocus();
        }
    }

    private void hideSportsOverlay() {
        sportsVisible = false;
        sportsOverlay.setVisibility(View.GONE);
        syncModeForUrl(webView.getUrl());
    }

    private void fetchTelegramMatches() {
        sportsStatusText.setText("Fetching live matches from t.me/totalsporteki…");
        matchesListContainer.removeAllViews();
        bgExecutor.execute(() -> {
            List<MatchItem> items = new ArrayList<>();
            try {
                String html = httpGet(TELEGRAM_CHANNEL_URL, null);
                Pattern msgPat = Pattern.compile(
                    "<div class=\"tgme_widget_message_text[^>]*>(.*?)</div>", Pattern.DOTALL);
                Matcher m = msgPat.matcher(html);
                while (m.find()) {
                    String block = m.group(1);
                    Matcher linkM = Pattern.compile("href=[\"'](https?://[^\"']+)[\"']").matcher(block);
                    String matchUrl = null;
                    while (linkM.find()) {
                        String candidate = linkM.group(1);
                        if (!candidate.contains("t.me/")) {
                            matchUrl = candidate;
                            break;
                        }
                    }
                    if (matchUrl == null) continue;
                    String text = block.replaceAll("<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").trim();
                    String[] lines = text.split("\n+");
                    String comp = "LIVE MATCH";
                    String title = matchUrl;
                    String time = "Live Stream";
                    List<String> nonEmpty = new ArrayList<>();
                    for (String l : lines) {
                        String t = l.trim();
                        if (!t.isEmpty()) nonEmpty.add(t);
                    }
                    if (nonEmpty.size() >= 2) {
                        comp = nonEmpty.get(0);
                        title = nonEmpty.get(1);
                    }
                    for (String l : nonEmpty) {
                        if (l.contains("GMT") || l.contains("Kickoff") || l.contains("🕐")) {
                            time = l;
                        }
                    }
                    items.add(new MatchItem(comp, title, time, matchUrl));
                }
                Collections.reverse(items);
            } catch (Exception e) {
                handler.post(() -> sportsStatusText.setText("Error fetching Telegram feed: " + e.getMessage()));
                return;
            }
            handler.post(() -> {
                matchesListContainer.removeAllViews();
                if (items.isEmpty()) {
                    sportsStatusText.setText("No active match links found right now on t.me/totalsporteki");
                    return;
                }
                sportsStatusText.setText("Found " + items.size() + " live events on t.me/totalsporteki · Select a match");
                for (int i = 0; i < items.size(); i++) {
                    MatchItem item = items.get(i);
                    View card = createMatchCard(item);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = px(8);
                    matchesListContainer.addView(card, lp);
                    if (i == 0) {
                        card.requestFocus();
                        fetchMatchServers(item);
                    }
                }
            });
        });
    }

    private View createMatchCard(MatchItem item) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(14), px(10), px(14), px(10));
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);

        TextView comp = new TextView(this);
        comp.setText(item.competition + "  ·  " + item.timeInfo);
        comp.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        comp.setSingleLine(true);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);

        box.addView(comp);
        box.addView(title);

        Runnable paint = () -> {
            boolean f = box.hasFocus();
            box.setBackground(shape(f ? 0xFF10B981 : 0xFF1B2234, 12, f ? Color.WHITE : 0x22FFFFFF, f ? 2f : 1f));
            comp.setTextColor(f ? 0xFF06281E : 0xFF10B981);
            title.setTextColor(f ? 0xFF06281E : Color.WHITE);
        };
        paint.run();
        box.setOnFocusChangeListener((v, f) -> paint.run());
        box.setOnClickListener(v -> fetchMatchServers(item));
        return box;
    }

    private void fetchMatchServers(MatchItem item) {
        serversHeaderText.setText("LOADING SERVERS FOR: " + item.title);
        serversListContainer.removeAllViews();
        bgExecutor.execute(() -> {
            List<ServerItem> servers = new ArrayList<>();
            try {
                String html = httpGet(item.link, "https://t.me/");
                Pattern aPat = Pattern.compile(
                    "<a[^>]+href=[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher m = aPat.matcher(html);
                Set<String> seen = new HashSet<>();
                while (m.find()) {
                    String href = m.group(1).trim();
                    if (href.contains("totalsportek.today") || href.contains("totalsportek24") ||
                        href.contains("footybite") || href.contains("nbabite") ||
                        href.contains("nflbite") || href.contains("sportsurge") ||
                        href.contains("soccerstreams") || href.contains("hofoot") ||
                        href.contains("twitter.com") || href.contains("t.me")) {
                        continue;
                    }
                    if (!seen.add(href)) continue;
                    String label = m.group(2).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                    if (label.isEmpty() || label.length() < 3) {
                        label = Uri.parse(href).getHost();
                    }
                    servers.add(new ServerItem(label, href));
                }
            } catch (Exception e) {
                handler.post(() -> serversHeaderText.setText("Failed to load servers: " + e.getMessage()));
                return;
            }
            handler.post(() -> {
                serversListContainer.removeAllViews();
                if (servers.isEmpty()) {
                    // Fallback: open the match page directly in Brave TV with shields active
                    serversHeaderText.setText("DIRECT MATCH STREAM (" + item.title + ")");
                    View directBtn = createServerCard(new ServerItem("▶ Open Match Page Directly (Shields ON)", item.link), 1);
                    serversListContainer.addView(directBtn);
                    return;
                }
                serversHeaderText.setText("2. PICK SERVER FOR " + item.title + " (" + servers.size() + " SERVERS)");
                int idx = 1;
                for (ServerItem srv : servers) {
                    View card = createServerCard(srv, idx++);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = px(7);
                    serversListContainer.addView(card, lp);
                }
            });
        });
    }

    private View createServerCard(ServerItem srv, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), px(10), px(14), px(10));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);

        TextView num = new TextView(this);
        num.setText("SERVER #" + index);
        num.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11.5f));
        num.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        row.addView(num, marginRight(px(86), -2, 10));

        TextView name = new TextView(this);
        name.setText(srv.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13.5f));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        Runnable paint = () -> {
            boolean f = row.hasFocus();
            row.setBackground(shape(f ? Color.WHITE : 0xFF1B2234, 10, f ? 0xFF10B981 : 0x22FFFFFF, f ? 2f : 1f));
            num.setTextColor(f ? 0xFF065F46 : 0xFF10B981);
            name.setTextColor(f ? 0xFF111827 : Color.WHITE);
        };
        paint.run();
        row.setOnFocusChangeListener((v, f) -> paint.run());
        row.setOnClickListener(v -> launchCleanSportsServer(srv));
        return row;
    }

    private void launchCleanSportsServer(ServerItem srv) {
        inSportsStreamMode = true;
        hideSportsOverlay();
        sportsStatusText.setText("Playing: " + srv.name);
        webView.getSettings().setUserAgentString(DESKTOP_UA);
        syncModeForUrl(srv.url);

        // Deep-unwrap nested player iframes in background so we skip the outer ad-wrapper webpage completely!
        bgExecutor.execute(() -> {
            String targetUrl = srv.url;
            String referer = "http://sportslinks.is/";
            try {
                for (int depth = 0; depth < 2; depth++) {
                    String html = httpGet(targetUrl, referer);
                    Matcher m = Pattern.compile(
                        "<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                    String foundIframe = null;
                    while (m.find()) {
                        String candidate = m.group(1).trim();
                        if (candidate.isEmpty() || candidate.startsWith("about:") || candidate.startsWith("javascript:")) continue;
                        String low = candidate.toLowerCase(Locale.US);
                        if (low.contains("doubleclick") || low.contains("googlesyndication") ||
                            low.contains("adsterra") || low.contains("exoclick") || low.contains("czernik")) {
                            continue;
                        }
                        if (candidate.startsWith("//")) {
                            candidate = "https:" + candidate;
                        } else if (candidate.startsWith("/")) {
                            URL base = new URL(targetUrl);
                            candidate = base.getProtocol() + "://" + base.getHost() + candidate;
                        }
                        foundIframe = candidate;
                        break;
                    }
                    if (foundIframe != null && !foundIframe.equals(targetUrl)) {
                        referer = targetUrl;
                        targetUrl = foundIframe;
                    } else {
                        break;
                    }
                }
            } catch (Exception ignored) {}

            final String finalUrl = targetUrl;
            final String finalReferer = referer;
            String host = Uri.parse(finalUrl).getHost();
            activeStreamHost = host != null ? host.toLowerCase(Locale.US) : "";

            handler.post(() -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", finalReferer);
                syncModeForUrl(finalUrl);
                webView.loadUrl(finalUrl, headers);
            });
        });
    }

    private String httpGet(String urlStr, String referer) throws Exception {
        String current = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", DESKTOP_UA);
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) break;
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
        throw new Exception("Too many redirects");
    }

    // =========================================================================
    // TOP BAR & NAVIGATION
    // =========================================================================
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
        View sportsBtn = barButton("⚽ Live Soccer", 0xFF059669, this::showSportsOverlay);
        View backBtn = barButton("←", 0x26FFFFFF, () -> { if (webView.canGoBack()) webView.goBack(); hideTopBar(); });
        View reloadBtn = barButton("↻", 0x26FFFFFF, () -> { webView.reload(); hideTopBar(); });

        row1.addView(ytBtn, marginRight(px(102), px(36), 6));
        row1.addView(sportsBtn, marginRight(px(108), px(36), 6));
        row1.addView(backBtn, marginRight(px(38), px(36), 6));
        row1.addView(reloadBtn, marginRight(px(38), px(36), 8));

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

        row1.addView(shieldBtn, marginRight(px(114), px(36), 6));
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
                if ("brave://sports".equals(targetUrl)) {
                    showSportsOverlay();
                } else {
                    loadTarget(targetUrl);
                    hideTopBar();
                }
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
        if (isYouTubeTvMode() && !sportsVisible) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }
    }

    private void loadTarget(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.equalsIgnoreCase("brave://sports")) {
            showSportsOverlay();
            return;
        }
        inSportsStreamMode = false;
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
                if (sportsVisible) hideSportsOverlay();
                if (barVisible) hideTopBar(); else showTopBar();
            }
            return true;
        }

        // When Live Sports Hub overlay is open, D-pad navigates match & server cards natively
        if (sportsVisible) {
            if (key == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
                hideSportsOverlay();
                return true;
            }
            return super.dispatchKeyEvent(event);
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
                    if (now - lastBackPress < 400) {
                        showTopBar();
                        return true;
                    }
                    lastBackPress = now;
                    webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                    webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // In Sports Stream Mode: pressing BACK returns straight to the Live Sports server picker!
        if (key == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (customVideoView != null) {
                    webView.getWebChromeClient().onHideCustomView();
                } else if (inSportsStreamMode) {
                    showSportsOverlay();
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
            c.drawCircle(cursorX, cursorY, px(3f), paint);
        }
    }
}
