package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.RenderProcessGoneDetail;
import android.widget.FrameLayout;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android WebDisplays browser host using DroidBridge's WDBRIDGE/1 frame and input protocol. */
public final class AndroidWebDisplaysBridge {
    private static final String TAG = "FlintWebDisplays";
    private static final int PORT = 17864;
    private static final int MAX_BROWSERS = 12;
    private static final int OP_FRAME = 100;

    private final Activity activity;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<Integer, Browser> browsers = new ConcurrentHashMap<>();
    private final Map<Integer, Frame> latestFrames = new ConcurrentHashMap<>();
    private HandlerThread frameThread;
    private Handler frameHandler;
    private Handler webHandler;
    private Thread transportThread;
    private volatile Socket socket;
    private volatile int captureFps = 30;
    private volatile int captureWidth = 512;
    private FrameLayout fallbackHost;

    public AndroidWebDisplaysBridge(Activity activity) { this.activity = activity; }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        webHandler = new Handler(Looper.getMainLooper());
        frameThread = new HandlerThread("Flint-WebDisplays-Frames");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        captureFps = readFps(activity);
        captureWidth = readWidth(activity);
        transportThread = new Thread(this::transportLoop, "Flint-WebDisplays-Transport");
        transportThread.setDaemon(true);
        transportThread.start();
        Log.i(TAG, "WebDisplays host started at " + captureFps + " FPS, " + captureWidth + "px capture width");
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        Socket active = socket;
        if (active != null) try { active.close(); } catch (IOException ignored) { }
        if (webHandler != null) webHandler.post(() -> {
            for (Integer id : browsers.keySet()) destroyBrowser(id);
        });
        if (frameThread != null) frameThread.quitSafely();
    }

    private void transportLoop() {
        while (running.get()) {
            try (Socket connection = new Socket()) {
                connection.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 650);
                connection.setTcpNoDelay(true);
                connection.setSoTimeout(3000);
                socket = connection;
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));
                     DataInputStream input = new DataInputStream(new BufferedInputStream(connection.getInputStream()))) {
                    output.writeUTF("WDBRIDGE/1");
                    output.flush();
                    if (!"READY".equals(input.readUTF())) throw new IOException("WebDisplays mod rejected bridge protocol");
                    Log.i(TAG, "Connected to WebDisplays mod");
                    ArrayList<Frame> sending = new ArrayList<>(MAX_BROWSERS);
                    while (running.get() && socket == connection) {
                        try {
                            if (input.available() > 0) {
                                int opcode = input.readUnsignedByte();
                                readCommand(input, opcode);
                            } else {
                                try { Thread.sleep(5L); }
                                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                            }
                        } catch (SocketTimeoutException timeout) {
                            // The socket was idle; retry without dropping the mod connection.
                        }
                        sending.clear();
                        try {
                            for (Integer id : latestFrames.keySet()) {
                                Frame frame = latestFrames.remove(id);
                                if (frame == null) continue;
                                sending.add(frame);
                                output.writeByte(OP_FRAME);
                                output.writeInt(frame.id);
                                output.writeInt(frame.width);
                                output.writeInt(frame.height);
                                output.writeInt(frame.rgba.length);
                                output.write(frame.rgba);
                            }
                            if (!sending.isEmpty()) output.flush();
                        } finally {
                            for (Frame frame : sending) frame.owner.recycleFrame(frame.rgba);
                            sending.clear();
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                if (running.get()) Log.d(TAG, "WebDisplays transport reconnecting: " + error.getMessage());
            } finally {
                socket = null;
                clearLatestFrames();
            }
            if (running.get()) try { Thread.sleep(1500L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void postWebCommand(Runnable command) {
        webHandler.post(() -> {
            try { command.run(); }
            catch (RuntimeException error) { Log.w(TAG, "Browser command failed", error); }
        });
    }

    private void readCommand(DataInputStream in, int opcode) throws IOException {
        final int id = in.readInt();
        switch (opcode) {
            case 1: {
                String url = in.readUTF();
                int width = in.readInt(), height = in.readInt();
                in.readBoolean();
                postWebCommand(() -> createBrowser(id, url, width, height));
                break;
            }
            case 3: postWebCommand(() -> destroyBrowser(id)); break;
            case 4: {
                String url = in.readUTF();
                postWebCommand(() -> loadUrl(id, url));
                break;
            }
            case 5: {
                int width = in.readInt(), height = in.readInt();
                postWebCommand(() -> resizeBrowser(id, width, height));
                break;
            }
            case 6: {
                String javascript = in.readUTF();
                if (javascript.length() > 65000) throw new IOException("WebDisplays JavaScript command too large");
                postWebCommand(() -> evaluate(id, javascript));
                break;
            }
            case 7: {
                int action = in.readUnsignedByte();
                in.readInt(); // pointer id
                int x = in.readInt(), y = in.readInt();
                postWebCommand(() -> touch(id, action, x, y));
                break;
            }
            case 8: {
                int action = in.readUnsignedByte();
                int key = in.readInt(), modifiers = in.readInt();
                char character = in.readChar();
                postWebCommand(() -> key(id, action, key, modifiers, character));
                break;
            }
            case 9: {
                int x = in.readInt(), y = in.readInt();
                postWebCommand(() -> scroll(id, x, y));
                break;
            }
            case 10: in.readBoolean(); break;
            default: throw new IOException("Unknown WebDisplays opcode " + opcode);
        }
    }

    private void createBrowser(int id, String url, int requestedWidth, int requestedHeight) {
        createBrowser(id, url, requestedWidth, requestedHeight, 0);
    }

    private void createBrowser(int id, String url, int requestedWidth, int requestedHeight, int rendererRecoveries) {
        destroyBrowser(id);
        if (browsers.size() >= MAX_BROWSERS) {
            Log.w(TAG, "Refusing browser " + id + ": maximum of " + MAX_BROWSERS + " active WebViews reached");
            return;
        }
        int[] size = captureSize(requestedWidth, requestedHeight);
        Browser browser = new Browser(id, Math.max(1, requestedWidth), Math.max(1, requestedHeight), size[0], size[1]);
        browser.rendererRecoveries = rendererRecoveries;
        try {
            ImageReader reader = ImageReader.newInstance(browser.width, browser.height, PixelFormat.RGBA_8888, 3);
            browser.reader = reader;
            DisplayManager manager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            if (manager == null) throw new IllegalStateException("DisplayManager unavailable");
            VirtualDisplay display = manager.createVirtualDisplay("Flint-WebDisplays-" + id,
                    browser.width, browser.height, 180, reader.getSurface(), 10);
            if (display == null) throw new IllegalStateException("VirtualDisplay unavailable");
            browser.display = display;
            Presentation presentation = new Presentation(activity, display.getDisplay());
            browser.presentation = presentation;
            Window window = presentation.getWindow();
            if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            FrameLayout page = new FrameLayout(presentation.getContext());
            WebView webView = new WebView(presentation.getContext());
            browser.webView = webView;
            configure(webView, browser);
            page.addView(webView, new FrameLayout.LayoutParams(browser.width, browser.height));
            presentation.setContentView(page);
            browser.reader = reader;
            browser.display = display;
            browser.presentation = presentation;
            browser.webView = webView;
            browser.page = page;
            reader.setOnImageAvailableListener(source -> capture(browser, source), frameHandler);
            browsers.put(id, browser);
            presentation.show();
            webView.requestFocus();
            browser.currentUrl = normalizeUrl(url);
            webView.loadUrl(browser.currentUrl);
            browser.frameWatchdog = () -> {
                if (!browser.closed && !browser.softwareCapture && browser.lastFrame == 0) {
                    Log.w(TAG, "Virtual display produced no frame for browser " + id + "; switching to software capture");
                    switchToSoftware(browser, browser.currentUrl);
                }
            };
            webHandler.postDelayed(browser.frameWatchdog, 8000L);
            Log.i(TAG, "Created browser " + id + " at " + browser.width + "x" + browser.height);
        } catch (Throwable error) {
            Log.w(TAG, "Virtual browser setup failed for " + id + "; switching to software capture", error);
            switchToSoftware(browser, url);
        }
    }

    private void switchToSoftware(Browser browser, String url) {
        if (!running.get() || browser.closed || browser.softwareCapture) return;
        browser.softwareCapture = true;
        releaseHardware(browser);
        try {
            if (browser.webView != null) {
                try { ((ViewGroup) browser.webView.getParent()).removeView(browser.webView); } catch (RuntimeException ignored) { }
                try { browser.webView.destroy(); } catch (RuntimeException ignored) { }
            }
            browser.webView = new WebView(activity);
            configure(browser.webView, browser);
            browser.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            browser.webView.setFocusable(true);
            browser.webView.setFocusableInTouchMode(false);
            if (fallbackHost == null) {
                fallbackHost = new FrameLayout(activity);
                fallbackHost.setClipChildren(false);
                fallbackHost.setClipToPadding(false);
                fallbackHost.setTranslationX(-8192f);
                activity.addContentView(fallbackHost, new ViewGroup.LayoutParams(1, 1));
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(browser.width, browser.height,
                    android.view.Gravity.TOP | android.view.Gravity.LEFT);
            browser.webView.setLayoutParams(params);
            fallbackHost.addView(browser.webView);
            browser.webView.measure(View.MeasureSpec.makeMeasureSpec(browser.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(browser.height, View.MeasureSpec.EXACTLY));
            browser.webView.layout(0, 0, browser.width, browser.height);
            browsers.put(browser.id, browser);
            browser.currentUrl = normalizeUrl(url);
            browser.webView.loadUrl(browser.currentUrl);
            browser.fallbackCapture = () -> captureSoftware(browser);
            webHandler.post(browser.fallbackCapture);
            Log.i(TAG, "Created software-captured browser " + browser.id + " at " + browser.width + "x" + browser.height);
        } catch (Throwable fallbackError) {
            Log.e(TAG, "Software browser setup failed for " + browser.id, fallbackError);
            destroyBrowser(browser.id);
            if (browser.webView != null) {
                try { browser.webView.destroy(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void releaseHardware(Browser browser) {
        if (browser.reader != null) {
            try { browser.reader.setOnImageAvailableListener(null, null); } catch (RuntimeException ignored) { }
            try { browser.reader.close(); } catch (RuntimeException ignored) { }
            browser.reader = null;
        }
        if (browser.display != null) {
            try { browser.display.release(); } catch (RuntimeException ignored) { }
            browser.display = null;
        }
        if (browser.presentation != null) {
            try { browser.presentation.dismiss(); } catch (RuntimeException ignored) { }
            browser.presentation = null;
        }
    }

    private void captureSoftware(Browser browser) {
        if (!running.get() || browser.closed || !browser.softwareCapture) return;
        long now = android.os.SystemClock.elapsedRealtimeNanos();
        int activeScreens = Math.max(1, (browsers.size() + 1) / 2);
        long frameInterval = 1_000_000_000L * activeScreens / captureFps;
        if (now - browser.lastFrame >= frameInterval) {
            byte[] rgba = null;
            try {
                browser.webView.measure(View.MeasureSpec.makeMeasureSpec(browser.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(browser.height, View.MeasureSpec.EXACTLY));
                browser.webView.layout(0, 0, browser.width, browser.height);
                if (browser.softwareBitmap == null || browser.softwareBitmap.isRecycled()) {
                    browser.softwareBitmap = Bitmap.createBitmap(browser.width, browser.height, Bitmap.Config.ARGB_8888);
                    browser.softwarePixels = new int[browser.width * browser.height];
                }
                if (browser.softwareCanvas == null) browser.softwareCanvas = new Canvas(browser.softwareBitmap);
                browser.webView.draw(browser.softwareCanvas);
                browser.softwareBitmap.getPixels(browser.softwarePixels, 0, browser.width, 0, 0, browser.width, browser.height);
                rgba = browser.obtainFrame(browser.width * browser.height * 4);
                for (int i = 0, j = 0; i < browser.softwarePixels.length; i++) {
                    int color = browser.softwarePixels[i];
                    rgba[j++] = (byte) (color >> 16);
                    rgba[j++] = (byte) (color >> 8);
                    rgba[j++] = (byte) color;
                    rgba[j++] = (byte) (color >>> 24);
                }
                browser.lastFrame = now;
                queueFrame(browser, browser.width, browser.height, rgba);
                rgba = null;
            } catch (RuntimeException error) {
                if (rgba != null) browser.recycleFrame(rgba);
                if (!browser.closed) Log.d(TAG, "Software frame capture skipped for " + browser.id + ": " + error.getMessage());
            }
        }
        if (!browser.closed && webHandler != null)
            webHandler.postDelayed(browser.fallbackCapture, Math.max(8, 1000 / captureFps));
    }

    private void configure(WebView view, Browser browser) {
        view.setBackgroundColor(0xffffffff);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView webView, String url, Bitmap favicon) {
                browser.currentUrl = normalizeUrl(url);
                Log.i(TAG, "Browser " + browser.id + " navigating to " + browser.currentUrl);
            }

            @Override public void onReceivedError(WebView webView, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame())
                    Log.w(TAG, "Browser " + browser.id + " main-frame load failed: " + error.getErrorCode() + " " + error.getDescription());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                String target = request.getUrl().toString();
                String normalized = normalizeUrl(target);
                Uri uri = Uri.parse(target);
                boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) ||
                        "about".equalsIgnoreCase(uri.getScheme()) && "about:blank".equalsIgnoreCase(target);
                if (!allowed) return true;
                browser.currentUrl = normalized;
                if (!normalized.equals(target)) {
                    webView.loadUrl(normalized);
                    return true;
                }
                return false;
            }

            @Override public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                String pageUrl = browser.currentUrl;
                Log.e(TAG, "WebView renderer exited for browser " + browser.id + " (crashed=" + detail.didCrash() + ")");
                int retries = ++browser.rendererRecoveries;
                destroyBrowser(browser.id, true);
                if (retries <= 2 && running.get()) {
                    webHandler.postDelayed(() -> {
                        if (running.get() && !browsers.containsKey(browser.id))
                            createBrowser(browser.id, pageUrl, browser.requestWidth, browser.requestHeight, retries);
                    }, retries * 1200L);
                }
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View customView, CustomViewCallback callback) {
                if (browser.presentation == null || browser.page == null || browser.closed || browser.fullscreenView != null) {
                    if (callback != null) callback.onCustomViewHidden();
                    return;
                }
                if (customView.getParent() instanceof ViewGroup) ((ViewGroup) customView.getParent()).removeView(customView);
                browser.fullscreenView = customView;
                browser.fullscreenCallback = callback;
                browser.webView.setVisibility(View.INVISIBLE);
                browser.page.addView(customView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Window window = browser.presentation.getWindow();
                if (window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                customView.setFocusable(true);
                customView.requestFocus();
            }

            @Override public void onHideCustomView() { exitFullscreen(browser, false); }
        });
    }

    private void exitFullscreen(Browser browser, boolean notifyWebView) {
        View fullscreen = browser.fullscreenView;
        if (fullscreen == null) return;
        browser.fullscreenView = null;
        if (fullscreen.getParent() instanceof ViewGroup) ((ViewGroup) fullscreen.getParent()).removeView(fullscreen);
        if (browser.webView != null) browser.webView.setVisibility(View.VISIBLE);
        Window window = browser.presentation == null ? null : browser.presentation.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(0);
        }
        WebChromeClient.CustomViewCallback callback = browser.fullscreenCallback;
        browser.fullscreenCallback = null;
        if (notifyWebView && callback != null) {
            try { callback.onCustomViewHidden(); } catch (RuntimeException ignored) { }
        }
    }

    private void capture(Browser browser, ImageReader source) {
        Image image = null;
        byte[] bytes = null;
        boolean queued = false;
        try {
            image = source.acquireLatestImage();
            if (image == null || !running.get() || browser.closed || browser.softwareCapture) return;
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            int activeScreens = Math.max(1, (browsers.size() + 1) / 2);
            if (now - browser.lastFrame < 1_000_000_000L * activeScreens / captureFps) return;
            Image.Plane plane = image.getPlanes()[0];
            int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
            int width = image.getWidth(), height = image.getHeight();
            int outWidth = browser.width, outHeight = browser.height;
            if (width < 1 || height < 1 || pixelStride < 4 || rowStride < width * pixelStride) return;
            bytes = browser.obtainFrame(outWidth * outHeight * 4);
            ByteBuffer buffer = plane.getBuffer();
            if (pixelStride == 4 && width == outWidth && height == outHeight && rowStride == outWidth * 4) {
                ByteBuffer copy = buffer.duplicate();
                copy.position(0);
                copy.get(bytes);
            } else for (int y = 0; y < outHeight; y++) {
                int sy = Math.min(height - 1, y * height / outHeight);
                for (int x = 0; x < outWidth; x++) {
                    int sx = Math.min(width - 1, x * width / outWidth);
                    int src = sy * rowStride + sx * pixelStride;
                    int dst = (y * outWidth + x) * 4;
                    bytes[dst] = buffer.get(src);
                    bytes[dst + 1] = buffer.get(src + 1);
                    bytes[dst + 2] = buffer.get(src + 2);
                    bytes[dst + 3] = buffer.get(src + 3);
                }
            }
            browser.lastFrame = now;
            queueFrame(browser, outWidth, outHeight, bytes);
            queued = true;
        } catch (RuntimeException error) {
            if (!browser.closed) Log.d(TAG, "Frame capture skipped for " + browser.id + ": " + error.getMessage());
        } finally {
            if (!queued && bytes != null) browser.recycleFrame(bytes);
            if (image != null) image.close();
        }
    }

    private void queueFrame(Browser browser, int width, int height, byte[] rgba) {
        synchronized (browser) {
            if (browser.closed) {
                browser.recycleFrame(rgba);
                return;
            }
            Frame previous = latestFrames.put(browser.id, new Frame(browser, width, height, rgba));
            if (previous != null) previous.owner.recycleFrame(previous.rgba);
        }
    }

    private void clearLatestFrames() {
        for (Map.Entry<Integer, Frame> entry : latestFrames.entrySet()) {
            Frame frame = entry.getValue();
            if (latestFrames.remove(entry.getKey(), frame)) frame.owner.recycleFrame(frame.rgba);
        }
    }

    private void destroyBrowser(int id) {
        destroyBrowser(id, false);
    }

    private void destroyBrowser(int id, boolean rendererGone) {
        Browser b = browsers.get(id);
        if (b == null) return;
        synchronized (b) {
            if (!browsers.remove(id, b)) return;
            b.closed = true;
            Frame discarded = latestFrames.remove(id);
            if (discarded != null) discarded.owner.recycleFrame(discarded.rgba);
        }
        if (b.fallbackCapture != null && webHandler != null) webHandler.removeCallbacks(b.fallbackCapture);
        if (b.frameWatchdog != null && webHandler != null) webHandler.removeCallbacks(b.frameWatchdog);
        exitFullscreen(b, !rendererGone);
        if (!rendererGone) try { b.webView.stopLoading(); b.webView.loadUrl("about:blank"); } catch (RuntimeException ignored) { }
        try { ((ViewGroup) b.webView.getParent()).removeView(b.webView); } catch (RuntimeException ignored) { }
        try { b.webView.destroy(); } catch (RuntimeException ignored) { }
        releaseHardware(b);
        if (b.softwareBitmap != null) {
            b.softwareBitmap.recycle();
            b.softwareBitmap = null;
            b.softwarePixels = null;
            b.softwareCanvas = null;
        }
        if (browsers.isEmpty() && fallbackHost != null) {
            try { ((ViewGroup) fallbackHost.getParent()).removeView(fallbackHost); } catch (RuntimeException ignored) { }
            fallbackHost = null;
        }
    }

    private void loadUrl(int id, String url) {
        Browser b = browsers.get(id);
        if (b != null && !b.closed) {
            b.currentUrl = normalizeUrl(url);
            b.webView.loadUrl(b.currentUrl);
        }
    }

    private void evaluate(int id, String javascript) {
        Browser b = browsers.get(id);
        if (b != null && !b.closed) b.webView.evaluateJavascript(javascript, null);
    }

    private void resizeBrowser(int id, int width, int height) {
        Browser old = browsers.get(id);
        if (old == null) return;
        int[] size = captureSize(width, height);
        old.requestWidth = Math.max(1, width);
        old.requestHeight = Math.max(1, height);
        if (size[0] == old.width && size[1] == old.height) return;
        String url = old.currentUrl;
        createBrowser(id, url == null ? "about:blank" : url, width, height, old.rendererRecoveries);
    }

    private void touch(int id, int action, int x, int y) {
        Browser b = browsers.get(id);
        if (b == null || b.closed) return;
        float px = Math.max(0, Math.min(b.webView.getWidth() - 1, x * b.webView.getWidth() / (float) Math.max(1, b.requestWidth)));
        float py = Math.max(0, Math.min(b.webView.getHeight() - 1, y * b.webView.getHeight() / (float) Math.max(1, b.requestHeight)));
        int masked = action == 1 ? MotionEvent.ACTION_UP : action == 2 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_DOWN;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, masked, px, py, 0);
        try { b.webView.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private void key(int id, int action, int key, int modifiers, char character) {
        Browser browser = browsers.get(id);
        if (browser == null || browser.closed) return;
        String escapedKey = JSONObject.quote(keyName(key));
        String escapedCharacter = JSONObject.quote(character == 0 ? "" : String.valueOf(character));
        String script = "(function(){try{var d=document,e=d.activeElement;while(e&&e.shadowRoot&&e.shadowRoot.activeElement)e=e.shadowRoot.activeElement;function edit(n){return n&&((/^(INPUT|TEXTAREA)$/.test(n.tagName)&&!n.readOnly&&!n.disabled)||n.isContentEditable)};if(!edit(e)){var a=d.querySelectorAll('input,textarea,[contenteditable=true]');for(var i=0;i<a.length;i++){var r=a[i].getBoundingClientRect();if(edit(a[i])&&r.width>0&&r.height>0){e=a[i];break}}}if(!e)e=d.body||d;var act=" + action + ",kc=" + key + ",mods=" + modifiers + ",ch=" + escapedCharacter + ",name=" + escapedKey + ";var ev=new KeyboardEvent(act===0?'keydown':'keyup',{key:name,code:name,bubbles:true,cancelable:true,ctrlKey:!!(mods&2),shiftKey:!!(mods&1),altKey:!!(mods&4),metaKey:!!(mods&8)});if(act!==2)e.dispatchEvent(ev);if(act===2&&ch){if(edit(e)){if(e.isContentEditable){d.execCommand&&d.execCommand('insertText',false,ch)}else{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,s=Object.getOwnPropertyDescriptor(p,'value'),a=e.selectionStart,b=e.selectionEnd;if(typeof a!=='number')a=e.value.length;if(typeof b!=='number')b=a;var v=e.value.slice(0,a)+ch+e.value.slice(b);if(s&&s.set)s.set.call(e,v);else e.value=v;e.setSelectionRange&&e.setSelectionRange(a+ch.length,a+ch.length)}e.dispatchEvent(new InputEvent('input',{bubbles:true,data:ch,inputType:'insertText'}))}else e.dispatchEvent(new KeyboardEvent('keypress',{key:ch,bubbles:true,cancelable:true}));return}if(act===0&&edit(e)&&!ev.defaultPrevented&&(kc===259||kc===261)){if(e.isContentEditable){d.execCommand&&d.execCommand(kc===259?'delete':'forwardDelete',false,null)}else if(typeof e.selectionStart==='number'){var a=e.selectionStart,b=e.selectionEnd;if(a===b){if(kc===259&&a>0)a--;if(kc===261&&b<e.value.length)b++}var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,s=Object.getOwnPropertyDescriptor(p,'value'),v=e.value.slice(0,a)+e.value.slice(b);if(s&&s.set)s.set.call(e,v);else e.value=v;e.setSelectionRange&&e.setSelectionRange(a,a);e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:kc===259?'deleteContentBackward':'deleteContentForward'}))}}else if(act===0&&kc===257&&e.tagName==='TEXTAREA'&&edit(e)){d.execCommand&&d.execCommand('insertText',false,'\n')}else if(act===0&&kc===257&&e.tagName==='INPUT'&&e.form){e.form.requestSubmit?e.form.requestSubmit():e.form.submit()}}catch(x){}})()";
        browser.webView.evaluateJavascript(script, null);
    }

    private void scroll(int id, int x, int y) {
        Browser b = browsers.get(id);
        if (b != null && !b.closed) b.webView.scrollBy(x, y);
    }

    private static String keyName(int key) {
        switch (key) {
            case 256: return "Escape"; case 257: return "Enter"; case 258: return "Tab";
            case 259: return "Backspace"; case 261: return "Delete"; case 262: return "ArrowRight";
            case 263: return "ArrowLeft"; case 264: return "ArrowDown"; case 265: return "ArrowUp";
            case 32: return " ";
            default: return key >= 32 && key <= 126 ? Character.toString((char) key) : "Unidentified";
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null || value.length() > 4096) return "about:blank";
        String url = value.trim();
        if (url.regionMatches(true, 0, "http://", 0, 7)) url = "https://" + url.substring(7);
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme) && "about:blank".equalsIgnoreCase(url))
                ? url : "about:blank";
    }

    private int[] captureSize(int width, int height) {
        width = Math.max(1, Math.min(2048, width));
        height = Math.max(1, Math.min(2048, height));
        float scale = Math.min(captureWidth / (float) width, (captureWidth * 9f / 16f) / height);
        return new int[]{Math.max(1, Math.min(854, Math.round(width * scale))),
                Math.max(1, Math.min(480, Math.round(height * scale)))};
    }

    private static int readFps(Context context) {
        int fps = context.getSharedPreferences("webdisplays_browser_performance", 0).getInt("capture_fps", 30);
        return fps == 15 || fps == 24 || fps == 30 || fps == 60 ? fps : 30;
    }

    private static int readWidth(Context context) {
        int width = context.getSharedPreferences("webdisplays_browser_performance", 0).getInt("capture_width", 512);
        return width == 256 || width == 512 || width == 854 ? width : 512;
    }

    private static final class Browser {
        final int id, width, height;
        volatile int requestWidth, requestHeight;
        volatile boolean closed;
        volatile long lastFrame;
        WebView webView;
        FrameLayout page;
        Presentation presentation;
        VirtualDisplay display;
        ImageReader reader;
        volatile boolean softwareCapture;
        Runnable fallbackCapture;
        Runnable frameWatchdog;
        String currentUrl = "about:blank";
        int rendererRecoveries;
        View fullscreenView;
        WebChromeClient.CustomViewCallback fullscreenCallback;
        Bitmap softwareBitmap;
        int[] softwarePixels;
        Canvas softwareCanvas;
        final ArrayBlockingQueue<byte[]> framePool = new ArrayBlockingQueue<>(2);
        Browser(int id, int requestWidth, int requestHeight, int width, int height) {
            this.id = id; this.requestWidth = requestWidth; this.requestHeight = requestHeight;
            this.width = width; this.height = height;
        }
        byte[] obtainFrame(int size) {
            byte[] buffer;
            while ((buffer = framePool.poll()) != null) if (buffer.length == size) return buffer;
            return new byte[size];
        }
        void recycleFrame(byte[] buffer) {
            if (buffer != null && buffer.length == width * height * 4) framePool.offer(buffer);
        }
    }

    private static final class Frame {
        final Browser owner;
        final int id, width, height;
        final byte[] rgba;
        Frame(Browser owner, int width, int height, byte[] rgba) {
            this.owner = owner; this.id = owner.id; this.width = width; this.height = height; this.rgba = rgba;
        }
    }
}
