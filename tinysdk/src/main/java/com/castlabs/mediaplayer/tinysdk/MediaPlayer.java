package com.castlabs.mediaplayer.tinysdk;

import android.media.MediaCodec.CryptoException;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.android.exoplayer.CodecCounters;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer.DecoderInitializationException;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.TimeRange;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer.MetadataRenderer;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.util.DebugTextViewHelper;
import com.google.android.exoplayer.util.PlayerControl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. It can be prepared
 * with one of a number of {@link RendererBuilder} classes to suit different use cases (e.g. DASH,
 * SmoothStreaming and so on).
 */
public class MediaPlayer implements ExoPlayer.Listener, ChunkSampleSource.EventListener,
    DefaultBandwidthMeter.EventListener, MediaCodecVideoTrackRenderer.EventListener, MediaCodecAudioTrackRenderer.EventListener,
    StreamingDrmSessionManager.EventListener, DashChunkSource.EventListener, TextRenderer,
    MetadataRenderer<Map<String, Object>>, DebugTextViewHelper.Provider, SurfaceHolder.Callback {

  /**
   * Builds renderers for the player.
   */
  public interface RendererBuilder {
    /**
     * Builds renderers for playback.
     *
     * @param player The player for which renderers are being built. {@link MediaPlayer#onRenderers}
     *     should be invoked once the renderers have been built. If building fails,
     *     {@link MediaPlayer#onRenderersError} should be invoked.
     */
    void buildRenderers(MediaPlayer player);
    /**
     * Cancels the current build operation, if there is one. Else does nothing.
     * <p>
     * A canceled build operation must not invoke {@link MediaPlayer#onRenderers} or
     * {@link MediaPlayer#onRenderersError} on the player, which may have been released.
     */
    void cancel();
  }

  /**
   * A listener for core events.
   */
  public interface Listener {
    void onStateChanged(boolean playWhenReady, int playbackState);
    void onError(Exception e);
    void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                            float pixelWidthHeightRatio);
  }

  /**
   * A listener for internal errors.
   * These errors are not visible to the user, and hence this listener is provided for
   * informational purposes only. Note however that an internal error may cause a fatal
   * error if the player fails to recover. If this happens, {@link Listener#onError(Exception)}
   * will be invoked.
   */
  private interface InternalErrorListener {
    void onRendererInitializationError(Exception e);
    void onAudioTrackInitializationError(AudioTrack.InitializationException e);
    void onAudioTrackWriteError(AudioTrack.WriteException e);
    void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs);
    void onDecoderInitializationError(DecoderInitializationException e);
    void onCryptoError(CryptoException e);
    void onLoadError(int sourceId, IOException e);
    void onDrmSessionManagerError(Exception e);
  }

  /**
   * A listener for debugging information.
   */
  public interface InfoListener {
    void onVideoFormatEnabled(Format format, int trigger, long mediaTimeMs);
    void onAudioFormatEnabled(Format format, int trigger, long mediaTimeMs);
    void onDroppedFrames(int count, long elapsed);
    void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate);
    void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                       long mediaStartTimeMs, long mediaEndTimeMs);
    void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                         long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs);
    void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                              long initializationDurationMs);
    void onAvailableRangeChanged(TimeRange availableRange);
  }

  /**
   * A listener for receiving notifications of timed text.
   */
  public interface CaptionListener {
    void onCues(List<Cue> cues);
  }

  /**
   * A listener for receiving ID3 metadata parsed from the media stream.
   */
  public interface Id3MetadataListener {
    void onId3Metadata(Map<String, Object> metadata);
  }

  // Constants pulled into this class for convenience.
  public static final int STATE_IDLE = ExoPlayer.STATE_IDLE;
  public static final int STATE_PREPARING = ExoPlayer.STATE_PREPARING;
  public static final int STATE_BUFFERING = ExoPlayer.STATE_BUFFERING;
  public static final int STATE_READY = ExoPlayer.STATE_READY;
  public static final int STATE_ENDED = ExoPlayer.STATE_ENDED;
  public static final int STATE_ERROR = 6;
  public static final int TRACK_DISABLED = ExoPlayer.TRACK_DISABLED;
  public static final int TRACK_DEFAULT = ExoPlayer.TRACK_DEFAULT;

  public static final int RENDERER_COUNT = 4;

  private static final int RENDERER_BUILDING_STATE_IDLE = 1;
  private static final int RENDERER_BUILDING_STATE_BUILDING = 2;
  private static final int RENDERER_BUILDING_STATE_BUILT = 3;

  private final RendererBuilder rendererBuilder;          // Concrete renderer for a playback session. HLS, SS or DASH.
  private final ExoPlayer player;                         // ExoPlayer does the actual work
  private final PlayerControl playerControl;              // Playback control: pause, resume, fast forward, rewind.
  private final Handler mainHandler;
  private final CopyOnWriteArrayList<Listener> listeners; // Objects subscribed to MediaPlayer events.

  // For internal player state management purposes
  private int rendererBuildingState;
  private int lastReportedPlaybackState;
  private boolean lastReportedPlayWhenReady;

  private Surface surface;                                // Main surface to be rendered. It is given by the application.
  private AspectRatioSurfaceView surfaceView;             // Main view given by the application.

  // Information recovered from the manifest
  private TrackRenderer videoRenderer;
  private CodecCounters codecCounters;
  private Format videoFormat;
  private int videoTrackToRestore;
  private BandwidthMeter bandwidthMeter;

  // Exoplayer callbacks
  private CaptionListener captionListener;             // Subtitles events.
  private Id3MetadataListener id3MetadataListener;     // For HLS & MPEG TS id3 information on audio tracks.
  private InternalErrorListener internalErrorListener; // Error message from ExoPlayer
  private InfoListener infoListener;                   // Notify format,

  /**
   * Main constructor {@link MediaPlayer}.
   *
   * @param playable Stream to be played.
   * @param configuration Provides a set of values required to start the playback session such as userAgent or the application context.
   */
  public MediaPlayer(Stream playable, PlayerConfiguration configuration) {
    this.rendererBuilder = RendererBuilderFactory.createRendererBuilder(playable, configuration);
    player = ExoPlayer.Factory.newInstance(RENDERER_COUNT, 1000, 5000);
    player.addListener(this);
    playerControl = new PlayerControl(player);
    mainHandler = new Handler();
    listeners = new CopyOnWriteArrayList<>();
    lastReportedPlaybackState = STATE_IDLE;
    rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    // Disable text initially.
    player.setSelectedTrack(TrackInfo.TYPE_TEXT, TRACK_DISABLED);
  }

  /**
   * Object to perform trick play: pause, resume, fast forward, rewind.
   */
  public PlayerControl getPlayerControl() {
    return playerControl;
  }

// ------------------------ MediaPlayer Listener setters ------------------------

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  public void setInternalErrorListener(InternalErrorListener listener) {
    internalErrorListener = listener;
  }

  public void setInfoListener(InfoListener listener) {
    infoListener = listener;
  }

  public void setCaptionListener(CaptionListener listener) {
    captionListener = listener;
  }

  public void setMetadataListener(Id3MetadataListener listener) {
    id3MetadataListener = listener;
  }

// ------------------------ MediaPlayer surface management ------------------------
  /**
   * Set AspectRatioSurfaceView to be painted. If this API is used there is no need to manage
   * video and view sizes on the app.
   *
   * @param aspectRatioSurfaceView Surface where the video is rendered.
   */
  public void setDisplay(AspectRatioSurfaceView aspectRatioSurfaceView) {
    this.surfaceView = aspectRatioSurfaceView;
    setSurface(aspectRatioSurfaceView.getHolder().getSurface());
  }

  public AspectRatioSurfaceView getDisplay() {
    return surfaceView;
  }

  public Surface getSurface() {
    return surface;
  }

  /**
   * Set AspectRatioSurfaceView to be painted. If this API is used aspect
   * ratio must be managed by the application using onVideoSizeChanged callback.
   *
   * @param surface Surface where the video is rendered.
   */
  public void setSurface(Surface surface) {
    this.surface = surface;
    pushSurface(false);
  }

  private void blockingClearSurface() {
    surface = null;
    pushSurface(true);
  }

  private void pushSurface(boolean blockForSurfacePush) {
    if (videoRenderer == null) {
        return;
    }
    if (blockForSurfacePush) {
        player.blockingSendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surface);
    } else {
        player.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surface);
    }
  }

   // SurfaceHolder.Callback implementation
   @Override
   public void surfaceCreated(SurfaceHolder holder) {
       setSurface(holder.getSurface());
   }

   @Override
   public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
       // Do nothing.
   }

   @Override
   public void surfaceDestroyed(SurfaceHolder holder) {
      if (player != null) {
        blockingClearSurface();
      }
   }

// ------------------------ MediaPlayer video, audio and subtitles track information and selection ------------------------

  public int getTrackCount(int type) {
    return player.getTrackCount(type);
  }

  public TrackInfo getTrack(int type, int index) {
      MediaFormat mediaFormat = player.getTrackFormat(type, index);
      TrackInfo trackInfo = new TrackInfo(mediaFormat);
      return trackInfo;
  }

  public int getSelectedTrack(int type) {
    return player.getSelectedTrack(type);
  }

  public void setSelectedTrack(int type, int index) {
    player.setSelectedTrack(type, index);
    if (type == TrackInfo.TYPE_TEXT && index < 0 && captionListener != null) {
      captionListener.onCues(Collections.<Cue>emptyList());
    }
  }

// ------------------------ MediaPlayer core methods ------------------------

  public void prepare() {
    if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT) {
      player.stop();
    }
    player.setPlayWhenReady(true);
    rendererBuilder.cancel();
    videoFormat = null;
    videoRenderer = null;
    rendererBuildingState = RENDERER_BUILDING_STATE_BUILDING;
    maybeReportPlayerState();
    rendererBuilder.buildRenderers(this);
  }

  public void release() {
    rendererBuilder.cancel();
    rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    surface = null;
    player.release();
  }

  public int getPlaybackState() {
    if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILDING) {
        return STATE_PREPARING;
    }
    int playerState = player.getPlaybackState();
    if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT && playerState == STATE_IDLE) {
        // This is an edge case where the renderers are built, but are still being passed to the player's playback thread.
        return STATE_PREPARING;
    }
    return playerState;
  }

  private void maybeReportPlayerState() {
    boolean playWhenReady = player.getPlayWhenReady();
    int playbackState = getPlaybackState();
    if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != playbackState) {
        for (Listener listener : listeners) {
            listener.onStateChanged(playWhenReady, playbackState);
        }
        lastReportedPlayWhenReady = playWhenReady;
        lastReportedPlaybackState = playbackState;
    }
  }

  /**
   * Invoked with the results from a {@link RendererBuilder}.
   *
   * @param renderers Renderers indexed by {@link MediaPlayer} TYPE_* constants. An individual
   *     element may be null if there do not exist tracks of the corresponding type.
   * @param bandwidthMeter Provides an estimate of the currently available bandwidth. May be null.
   */
  void onRenderers(TrackRenderer[] renderers, BandwidthMeter bandwidthMeter) {
    for (int i = 0; i < RENDERER_COUNT; i++) {
      if (renderers[i] == null) {
        // Convert a null renderer to a dummy renderer.
        renderers[i] = new DummyTrackRenderer();
      }
    }
    // Complete preparation.
    this.videoRenderer = renderers[TrackInfo.TYPE_VIDEO];
    this.codecCounters = videoRenderer instanceof MediaCodecTrackRenderer
        ? ((MediaCodecTrackRenderer) videoRenderer).codecCounters
        : renderers[TrackInfo.TYPE_AUDIO] instanceof MediaCodecTrackRenderer
        ? ((MediaCodecTrackRenderer) renderers[TrackInfo.TYPE_AUDIO]).codecCounters : null;
    this.bandwidthMeter = bandwidthMeter;
    pushSurface(false);
    player.prepare(renderers);
    rendererBuildingState = RENDERER_BUILDING_STATE_BUILT;
  }

  /**
   * Invoked if a {@link RendererBuilder} encounters an error.
   *
   * @param e Describes the error.
   */
  void onRenderersError(Exception e) {
    if (internalErrorListener != null) {
      internalErrorListener.onRendererInitializationError(e);
    }
    for (Listener listener : listeners) {
      listener.onError(e);
    }
    rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    maybeReportPlayerState();
  }

  public void seekTo(long positionMs) {
    player.seekTo(positionMs);
  }

// ------------------------ MediaPlayer metadata information ------------------------

  public Format getFormat() {
    return videoFormat;
  }

  public BandwidthMeter getBandwidthMeter() {
    return bandwidthMeter;
  }

  public CodecCounters getCodecCounters() {
    return codecCounters;
  }

  public long getCurrentPosition() {
    return player.getCurrentPosition();
  }

  public long getDuration() {
    return player.getDuration();
  }

  public int getBufferedPercentage() {
      return player.getBufferedPercentage();
  }

  public boolean getPlayWhenReady() {
      return player.getPlayWhenReady();
  }

  Looper getPlaybackLooper() {
      return player.getPlaybackLooper();
  }

  Handler getMainHandler() {
    return mainHandler;
  }

// ------------------------ MediaPlayer callbacks ------------------------

  @Override
  public void onPlayerStateChanged(boolean playWhenReady, int state) {
    maybeReportPlayerState();
  }

  @Override
  public void onPlayerError(ExoPlaybackException exception) {
    rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    for (Listener listener : listeners) {
      listener.onError(exception);
    }
  }

  @Override
  public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
    surfaceView.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);
    for (Listener listener : listeners) {
        listener.onVideoSizeChanged(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
    }
  }

  @Override
  public void onDroppedFrames(int count, long elapsed) {
    if (infoListener != null) {
        infoListener.onDroppedFrames(count, elapsed);
    }
  }

  @Override
  public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
    if (infoListener != null) {
       infoListener.onBandwidthSample(elapsedMs, bytes, bitrateEstimate);
    }
  }

  @Override
  public void onDownstreamFormatChanged(int sourceId, Format format, int trigger,
      long mediaTimeMs) {
    if (infoListener == null) {
      return;
    }
    if (sourceId == TrackInfo.TYPE_VIDEO) {
      videoFormat = format;
      infoListener.onVideoFormatEnabled(format, trigger, mediaTimeMs);
    } else if (sourceId == TrackInfo.TYPE_AUDIO) {
      infoListener.onAudioFormatEnabled(format, trigger, mediaTimeMs);
    }
  }

  @Override
  public void onDrmKeysLoaded() {
    // Do nothing.
  }

  @Override
  public void onDrmSessionManagerError(Exception e) {
    if (internalErrorListener != null) {
      internalErrorListener.onDrmSessionManagerError(e);
    }
  }

  @Override
  public void onDecoderInitializationError(DecoderInitializationException e) {
    if (internalErrorListener != null) {
      internalErrorListener.onDecoderInitializationError(e);
    }
  }

  @Override
  public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
    if (internalErrorListener != null) {
      internalErrorListener.onAudioTrackInitializationError(e);
    }
  }

  @Override
  public void onAudioTrackWriteError(AudioTrack.WriteException e) {
    if (internalErrorListener != null) {
      internalErrorListener.onAudioTrackWriteError(e);
    }
  }

  @Override
  public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
    if (internalErrorListener != null) {
        internalErrorListener.onAudioTrackUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
    }
  }

  @Override
  public void onCryptoError(CryptoException e) {
    if (internalErrorListener != null) {
        internalErrorListener.onCryptoError(e);
    }
  }

  @Override
  public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
      long initializationDurationMs) {
      if (infoListener != null) {
          infoListener.onDecoderInitialized(decoderName, elapsedRealtimeMs, initializationDurationMs);
    }
  }

  @Override
  public void onLoadError(int sourceId, IOException e) {
    if (internalErrorListener != null) {
        internalErrorListener.onLoadError(sourceId, e);
    }
  }

  @Override
  public void onCues(List<Cue> cues) {
    if (captionListener != null && getSelectedTrack(TrackInfo.TYPE_TEXT) != TRACK_DISABLED) {
      captionListener.onCues(cues);
    }
  }

  @Override
  public void onMetadata(Map<String, Object> metadata) {
    if (id3MetadataListener != null && getSelectedTrack(TrackInfo.TYPE_METADATA) != TRACK_DISABLED) {
        id3MetadataListener.onId3Metadata(metadata);
    }
  }

  @Override
  public void onAvailableRangeChanged(TimeRange availableRange) {
      if (infoListener != null) {
      infoListener.onAvailableRangeChanged(availableRange);
    }
  }

  @Override
  public void onPlayWhenReadyCommitted() {
    // Do nothing.
  }

  @Override
  public void onDrawnToSurface(Surface surface) {
    // Do nothing.
  }

  @Override
  public void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
    long mediaStartTimeMs, long mediaEndTimeMs) {
    if (infoListener != null) {
        infoListener.onLoadStarted(sourceId, length, type, trigger, format, mediaStartTimeMs,
                mediaEndTimeMs);
    }
  }

  @Override
  public void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
    long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs) {
    if (infoListener != null) {
      infoListener.onLoadCompleted(sourceId, bytesLoaded, type, trigger, format, mediaStartTimeMs,
               mediaEndTimeMs, elapsedRealtimeMs, loadDurationMs);
    }
  }

  @Override
  public void onLoadCanceled(int sourceId, long bytesLoaded) {
    // Do nothing.
  }

  @Override
  public void onUpstreamDiscarded(int sourceId, long mediaStartTimeMs, long mediaEndTimeMs) {
    // Do nothing.
  }

// ------------------------ Helpers ------------------------

  public static String playbackStateToString(int playbackState) {
    String text = "";
    switch(playbackState) {
        case MediaPlayer.STATE_BUFFERING:
            text += "buffering";
            break;
        case MediaPlayer.STATE_ENDED:
            text += "ended";
            break;
        case MediaPlayer.STATE_IDLE:
            text += "idle";
            break;
        case MediaPlayer.STATE_PREPARING:
            text += "preparing";
            break;
        case MediaPlayer.STATE_READY:
            text += "ready";
            break;
        default:
            text += "unknown";
            break;
    }
    return text;
  }
}
