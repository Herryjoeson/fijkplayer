//MIT License
//
//Copyright (c) [2019-2020] [Befovy]
//
//Permission is hereby granted, free of charge, to any person obtaining a copy
//of this software and associated documentation files (the "Software"), to deal
//in the Software without restriction, including without limitation the rights
//to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//copies of the Software, and to permit persons to whom the Software is
//furnished to do so, subject to the following conditions:
//
//The above copyright notice and this permission notice shall be included in all
//copies or substantial portions of the Software.
//
//THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//SOFTWARE.

package com.befovy.fijkplayer;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

public class FijkPlayer implements MethodChannel.MethodCallHandler,
        IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnErrorListener,
        IMediaPlayer.OnVideoSizeChangedListener,
        IMediaPlayer.OnInfoListener,
        IMediaPlayer.OnCompletionListener,
        IMediaPlayer.OnBufferingUpdateListener {

    final private static AtomicInteger atomicId = new AtomicInteger(0);

    final private static int idle = 0;
    final private static int initialized = 1;
    final private static int asyncPreparing = 2;
    @SuppressWarnings("unused")
    final private static int prepared = 3;
    @SuppressWarnings("unused")
    final private static int started = 4;
    final private static int paused = 5;
    final private static int completed = 6;
    final private static int stopped = 7;
    @SuppressWarnings("unused")
    final private static int error = 8;
    final private static int end = 9;

    final private int mPlayerId;
    final private IjkMediaPlayer mIjkMediaPlayer;
    final private FijkEngine mEngine;
    // non-local field prevent GC
    final private EventChannel mEventChannel;

    // non-local field prevent GC
    final private MethodChannel mMethodChannel;

    final private QueuingEventSink mEventSink = new QueuingEventSink();
    final private HostOption mHostOptions = new HostOption();

    private int mState;
    private int mRotate = 0;
    private int mWidth = 0;
    private int mHeight = 0;
    private TextureRegistry.SurfaceTextureEntry mSurfaceTextureEntry;
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    final private boolean mJustSurface;

    FijkPlayer(@NonNull FijkEngine engine, boolean justSurface) {
        mEngine = engine;
        mPlayerId = atomicId.incrementAndGet();
        mState = 0;
        mJustSurface = justSurface;
        if (justSurface) {
            mIjkMediaPlayer = null;
            mEventChannel = null;
            mMethodChannel = null;
        } else {
            mIjkMediaPlayer = new IjkMediaPlayer();
            mIjkMediaPlayer.setOnPreparedListener(this);
            mIjkMediaPlayer.setOnVideoSizeChangedListener(this);
            mIjkMediaPlayer.setOnErrorListener(this);
            mIjkMediaPlayer.setOnInfoListener(this);
            mIjkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-position-notify", 1);
            mIjkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);

            mMethodChannel = new MethodChannel(mEngine.messenger(), "befovy.com/fijkplayer/" + mPlayerId);
            mMethodChannel.setMethodCallHandler(this);

            mEventChannel = new EventChannel(mEngine.messenger(), "befovy.com/fijkplayer/event/" + mPlayerId);
            mEventChannel.setStreamHandler(new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink eventSink) {
                    mEventSink.setDelegate(eventSink);
                }

                @Override
                public void onCancel(Object o) {
                    mEventSink.setDelegate(null);
                }
            });
        }
    }

    int getPlayerId() {
        return mPlayerId;
    }

    void setup() {
        if (mJustSurface)
            return;
        mIjkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", "fcc-_es2");
    }

    long setupSurface() {
        setup();
        if (mSurfaceTextureEntry == null) {
            TextureRegistry.SurfaceTextureEntry surfaceTextureEntry = mEngine.createSurfaceEntry();
            mSurfaceTextureEntry = surfaceTextureEntry;
            if (surfaceTextureEntry != null) {
                mSurfaceTexture = surfaceTextureEntry.surfaceTexture();
                mSurface = new Surface(mSurfaceTexture);
            }
            if (!mJustSurface) {
                mIjkMediaPlayer.setSurface(mSurface);
            }
        }
        if (mSurfaceTextureEntry != null)
            return mSurfaceTextureEntry.id();
        else {
            Log.e("FIJKPLAYER", "setup surface, null SurfaceTextureEntry");
            return 0;
        }
    }

    void release() {
        if (!mJustSurface) {
            handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, end, mState, null);
            mIjkMediaPlayer.release();
        }
        if (mSurfaceTextureEntry != null) {
            mSurfaceTextureEntry.release();
            mSurfaceTextureEntry = null;
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (!mJustSurface) {
            mMethodChannel.setMethodCallHandler(null);
            mEventChannel.setStreamHandler(null);
        }
    }

    private boolean isPlayable(int state) {
        return state == started || state == paused || state == completed || state == prepared;
    }

    private void onStateChanged(int newState, int oldState) {
        if (newState == started && oldState != started) {
            mEngine.onPlayingChange(1);

            if (mHostOptions.getIntOption(HostOption.REQUEST_AUDIOFOCUS, 0) == 1) {
                mEngine.audioFocus(true);
            }

            if (mHostOptions.getIntOption(HostOption.REQUEST_SCREENON, 0) == 1) {
                mEngine.setScreenOn(true);
            }
        } else if (newState != started && oldState == started) {
            mEngine.onPlayingChange(-1);

            if (mHostOptions.getIntOption(HostOption.RELEASE_AUDIOFOCUS, 0) == 1) {
                mEngine.audioFocus(false);
            }

            if (mHostOptions.getIntOption(HostOption.REQUEST_SCREENON, 0) == 1) {
                mEngine.setScreenOn(false);
            }
        }

        if (isPlayable(newState) && !isPlayable(oldState)) {
            mEngine.onPlayableChange(1);
        } else if (!isPlayable(newState) && isPlayable(oldState)) {
            mEngine.onPlayableChange(-1);
        }
    }

    private void handleEvent(int what, int arg1, int arg2, Object extra) {
        Map<String, Object> event = new HashMap<>();

        switch (what) {
            case FijkEventConstants.PREPARED:
                event.put("event", "prepared");
                long duration = mIjkMediaPlayer.getDuration();
                event.put("duration", duration);
                mEventSink.success(event);
                break;
            case FijkEventConstants.PLAYBACK_STATE_CHANGED:
                mState = arg1;
                event.put("event", "state_change");
                event.put("new", arg1);
                event.put("old", arg2);
                onStateChanged(arg1, arg2);
                mEventSink.success(event);
                break;
            case FijkEventConstants.VIDEO_RENDERING_START:
            case FijkEventConstants.AUDIO_RENDERING_START:
                event.put("event", "rendering_start");
                event.put("type", what == FijkEventConstants.VIDEO_RENDERING_START ? "video" : "audio");
                mEventSink.success(event);
                break;
            case FijkEventConstants.BUFFERING_START:
            case FijkEventConstants.BUFFERING_END:
                event.put("event", "freeze");
                event.put("value", what == FijkEventConstants.BUFFERING_START);
                mEventSink.success(event);
                break;

            // buffer / cache position
            case FijkEventConstants.BUFFERING_UPDATE:
                event.put("event", "buffering");
                event.put("head", arg1);
                event.put("percent", arg2);
                mEventSink.success(event);
                break;
            case FijkEventConstants.CURRENT_POSITION_UPDATE:
                event.put("event", "pos");
                event.put("pos", arg1);
                mEventSink.success(event);
                break;
            case FijkEventConstants.VIDEO_ROTATION_CHANGED:
                event.put("event", "rotate");
                event.put("degree", arg1);
                mRotate = arg1;
                mEventSink.success(event);
                if (mWidth > 0 && mHeight > 0) {
                    handleEvent(FijkEventConstants.VIDEO_SIZE_CHANGED, mWidth, mHeight, null);
                }
                break;
            case FijkEventConstants.VIDEO_SIZE_CHANGED:
                event.put("event", "size_changed");
                if (mRotate == 0 || mRotate == 180) {
                    event.put("width", arg1);
                    event.put("height", arg2);
                    mEventSink.success(event);
                } else if (mRotate == 90 || mRotate == 270) {
                    event.put("width", arg2);
                    event.put("height", arg1);
                    mEventSink.success(event);
                }
                // default mRotate is -1 which means unknown
                // do not send event if mRotate is unknown
                mWidth = arg1;
                mHeight = arg2;
                break;
            case FijkEventConstants.SEEK_COMPLETE:
                event.put("event", "seek_complete");
                event.put("pos", arg1);
                event.put("err", arg2);
                mEventSink.success(event);
                break;
            case FijkEventConstants.ERROR:
                mEventSink.error(String.valueOf(arg1), extra.toString(), arg2);
                break;
            default:
                // Log.d("FLUTTER", "jonEvent:" + what);
                break;
        }
    }

    public void onEvent(IjkMediaPlayer ijkMediaPlayer, int what, int arg1, int arg2, Object extra) {
        switch (what) {
            case FijkEventConstants.PREPARED:
            case FijkEventConstants.PLAYBACK_STATE_CHANGED:
            case FijkEventConstants.BUFFERING_START:
            case FijkEventConstants.BUFFERING_END:
            case FijkEventConstants.BUFFERING_UPDATE:
            case FijkEventConstants.VIDEO_SIZE_CHANGED:
            case FijkEventConstants.ERROR:
            case FijkEventConstants.VIDEO_RENDERING_START:
            case FijkEventConstants.AUDIO_RENDERING_START:
            case FijkEventConstants.CURRENT_POSITION_UPDATE:
            case FijkEventConstants.VIDEO_ROTATION_CHANGED:
            case FijkEventConstants.SEEK_COMPLETE:
                handleEvent(what, arg1, arg2, extra);
                break;
            default:
                break;
        }
    }


    private void applyOptions(Object options) {
        if (options instanceof Map) {
            Map optionsMap = (Map) options;
            for (Object o : optionsMap.keySet()) {
                Object option = optionsMap.get(o);
                if (o instanceof Integer && option instanceof Map) {
                    int cat = (Integer) o;
                    Map optionMap = (Map) option;
                    for (Object key : optionMap.keySet()) {
                        Object value = optionMap.get(key);
                        if (key instanceof String && cat != 0) {
                            String name = (String) key;
                            if (value instanceof Integer) {
                                mIjkMediaPlayer.setOption(cat, name, (Integer) value);
                            } else if (value instanceof String) {
                                mIjkMediaPlayer.setOption(cat, name, (String) value);
                            }
                        } else if (key instanceof String) {
                            // cat == 0, hostCategory
                            String name = (String) key;
                            if (value instanceof Integer) {
                                mHostOptions.addIntOption(name, (Integer) value);
                            } else if (value instanceof String) {
                                mHostOptions.addStrOption(name, (String) value);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "setupSurface":
                long viewId = setupSurface();
                result.success(viewId);
                break;
            case "setOption":
                Integer category = call.argument("cat");
                final String key = call.argument("key");
                if (call.hasArgument("long")) {
                    final Integer value = call.argument("long");
                    if (category != null && category != 0) {
                        mIjkMediaPlayer.setOption(category, key, value != null ? value.longValue() : 0);
                    } else if (category != null) {
                        // cat == 0, hostCategory
                        mHostOptions.addIntOption(key, value);
                    }
                } else if (call.hasArgument("str")) {
                    final String value = call.argument("str");
                    if (category != null && category != 0) {
                        mIjkMediaPlayer.setOption(category, key, value);
                    } else if (category != null) {
                        // cat == 0, hostCategory
                        mHostOptions.addStrOption(key, value);
                    }
                } else {
                    Log.w("FIJKPLAYER", "error arguments for setOptions");
                }
                result.success(null);
                break;
            case "applyOptions":
                applyOptions(call.arguments);
                result.success(null);
                break;
            case "setDataSource":
                String url = call.argument("url");
                Uri uri = Uri.parse(url);
                boolean openAsset = false;
                if ("asset".equals(uri.getScheme())) {
                    openAsset = true;
                    String host = uri.getHost();
                    String path = uri.getPath() != null ? uri.getPath().substring(1) : "";
                    String asset = mEngine.lookupKeyForAsset(path, host);
                    if (!TextUtils.isEmpty(asset)) {
                        uri = Uri.parse(asset);
                    }
                }
                try {
                    Context context = mEngine.context();
                    if (openAsset && context != null) {
                        AssetManager assetManager = context.getAssets();
                        InputStream is = assetManager.open(uri.getPath() != null ? uri.getPath() : "", AssetManager.ACCESS_RANDOM);
                        mIjkMediaPlayer.setDataSource(new RawMediaDataSource(is));
                    } else if (context != null) {
                        if (TextUtils.isEmpty(uri.getScheme()) || "file".equals(uri.getScheme())) {
                            String path = uri.getPath() != null ? uri.getPath() : "";
                            IMediaDataSource dataSource = new FileMediaDataSource(new File(path));
                            mIjkMediaPlayer.setDataSource(dataSource);
                        } else {
                            mIjkMediaPlayer.setDataSource(mEngine.context(), uri);
                        }
                    } else {
                        Log.e("FIJKPLAYER", "context null, can't setDataSource");
                    }
                    handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, initialized, -1, null);
                    if (context == null) {
                        handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, error, -1, null);
                    }
                    result.success(null);
                } catch (FileNotFoundException e) {
                    result.error("-875574348", "Local File not found:" + e.getMessage(), null);
                } catch (IOException e) {
                    result.error("-1162824012", "Local IOException:" + e.getMessage(), null);
                }
                break;
            case "prepareAsync":
                setup();
                mIjkMediaPlayer.prepareAsync();
                handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, asyncPreparing, -1, null);
                result.success(null);
                break;
            case "start":
                mIjkMediaPlayer.start();
                result.success(null);
                break;
            case "pause":
                mIjkMediaPlayer.pause();
                result.success(null);
                break;
            case "stop":
                mIjkMediaPlayer.stop();
                handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, stopped, -1, null);
                result.success(null);
                break;
            case "reset":
                mIjkMediaPlayer.reset();
                handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, idle, -1, null);
                result.success(null);
                break;
            case "getCurrentPosition":
                long pos = mIjkMediaPlayer.getCurrentPosition();
                result.success(pos);
                break;
            case "setVolume":
                final Double volume = call.argument("volume");
                float vol = volume != null ? volume.floatValue() : 1.0f;
                mIjkMediaPlayer.setVolume(vol, vol);
                result.success(null);
                break;
            case "seekTo":
                final Integer msec = call.argument("msec");
                if (mState == completed)
                    handleEvent(FijkEventConstants.PLAYBACK_STATE_CHANGED, paused, -1, null);
                mIjkMediaPlayer.seekTo(msec != null ? msec.longValue() : 0);
                result.success(null);
                break;
            case "setLoop":
                final Integer loopCount = call.argument("loop");
//            mIjkMediaPlayer.setLoopCount(loopCount != null ? loopCount : 1);
                result.success(null);
                break;
            case "setSpeed":
                final Double speed = call.argument("speed");
                mIjkMediaPlayer.setSpeed(speed != null ? speed.floatValue() : 1.0f);
                result.success(null);
                break;
            default:

                result.notImplemented();
                break;
        }
    }

    @Override
    public void onPrepared(IMediaPlayer iMediaPlayer) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.PREPARED, 0, 0, new HashMap<String, String>());
    }

    @Override
    public boolean onError(IMediaPlayer iMediaPlayer, int i, int i1) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.ERROR, i, i1, new HashMap<String, String>());
        return false;
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int i, int i1, int i2, int i3) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.VIDEO_SIZE_CHANGED, i, i1, new HashMap<String, String>());
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer iMediaPlayer, int i) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.BUFFERING_UPDATE, i, 0, new HashMap<String, String>());
    }

    @Override
    public void onCompletion(IMediaPlayer iMediaPlayer) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.COMPLETED, 0, 0, new HashMap<String, String>());
    }

    @Override
    public boolean onInfo(IMediaPlayer iMediaPlayer, int i, int i1) {
        switch (i) {
            case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                onEvent(mIjkMediaPlayer, FijkEventConstants.BUFFERING_START, 0, 0, new HashMap<String, String>());
                break;

            case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                onEvent(mIjkMediaPlayer, FijkEventConstants.BUFFERING_END, 0, 0, new HashMap<String, String>());
                break;

            case IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START:
                onEvent(mIjkMediaPlayer, FijkEventConstants.AUDIO_RENDERING_START, 0, 0, new HashMap<String, String>());
                break;

            case IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                onEvent(mIjkMediaPlayer, FijkEventConstants.VIDEO_RENDERING_START, 0, 0, new HashMap<String, String>());
                break;

            default:
                onEvent(mIjkMediaPlayer, FijkEventConstants.FIND_STREAM_INFO, i, i1, new HashMap<String, String>());
                break;
        }

        return false;
    }

    @Override
    public void onSeekComplete(IMediaPlayer iMediaPlayer) {
        onEvent(mIjkMediaPlayer, FijkEventConstants.SEEK_COMPLETE, (int)iMediaPlayer.getCurrentPosition(), 0, new HashMap<String, String>());
    }
}
