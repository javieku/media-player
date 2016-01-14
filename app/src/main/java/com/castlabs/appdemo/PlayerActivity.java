package com.castlabs.appdemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.castlabs.appdemo.appDemo.R;
import com.castlabs.mediaplayer.tinysdk.AspectRatioSurfaceView;
import com.castlabs.mediaplayer.tinysdk.Stream;
import com.castlabs.mediaplayer.tinysdk.MediaPlayer;
import com.castlabs.mediaplayer.tinysdk.PlayerConfiguration;
import com.castlabs.mediaplayer.tinysdk.TrackInfo;
import com.getbase.floatingactionbutton.FloatingActionButton;

/**
 * An activity that plays media using {@link MediaPlayer}.
 */
public class PlayerActivity extends Activity implements MediaPlayer.Listener {

  private static final String TAG = "PlayerActivity";

  // Popup menu identifiers
  private static final int MENU_GROUP_ID_TRACKS = 1;
  private static final int TRACK_ID_OFFSET = 2;

  // UI declarations
  private MediaController mediaController;  // Trickplay control buttons
  private AspectRatioSurfaceView display;   // Surface to be rendered
  private FloatingActionButton videoButton; // Select video track
  private FloatingActionButton audioButton; // Select audio track
  private Button retryButton;               // Restart playback
  private TextView playerStateTextView;     // Show current player state

  // Mediaplayer elements
  private MediaPlayer player;               // MediaPlayer instance
  private long playerPosition;
  private Uri contentUri;                   // Asset URI to be played

// ------------------------ Activity lifecycle ------------------------
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.player_activity);
    View root = findViewById(R.id.root);
    root.setOnTouchListener(new OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                toggleControlsVisibility();
            } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                view.performClick();
            }
            return true;
        }
    });
      root.setOnKeyListener(new OnKeyListener() {
          @Override
          public boolean onKey(View v, int keyCode, KeyEvent event) {
              if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                      || keyCode == KeyEvent.KEYCODE_MENU) {
                  return false;
              }
              return mediaController.dispatchKeyEvent(event);
        }
    });

    playerStateTextView = (TextView) findViewById(R.id.player_state_view);
    display  = (AspectRatioSurfaceView) findViewById(R.id.surface_view);

    mediaController = new KeyCompatibleMediaController(this);
    mediaController.setAnchorView(root);

    retryButton = (Button) findViewById(R.id.retry_button);
    retryButton.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View view) {
            if (view == retryButton) {
                preparePlayer();
            }
        }
    });

    videoButton = (FloatingActionButton) findViewById(R.id.video_controls);
    audioButton = (FloatingActionButton) findViewById(R.id.audio_controls);
  }

  @Override
  public void onNewIntent(Intent intent) {
    releasePlayer();
    playerPosition = 0;
    setIntent(intent);
  }

  @Override
  public void onResume() {
    super.onResume();
    contentUri = getIntent().getData();
    preparePlayer();
  }

  @Override
  public void onPause() {
    super.onPause();
    releasePlayer();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releasePlayer();
  }

// ------------------------ MediaPlayer setup ------------------------

  private void createPlayer() {
      String applicationName = getResources().getString(R.string.app_name);
      Stream playable = new Stream(contentUri);
      PlayerConfiguration playerConfiguration = new PlayerConfiguration(this, applicationName);
      player = new MediaPlayer(playable, playerConfiguration);

      // Add callbacks
      player.addListener(this);

      // Go forward to playerPosition
      player.seekTo(playerPosition);

      // Connect MediaPlayer with GUI playback controls
      mediaController.setMediaPlayer(player.getPlayerControl());
      mediaController.setEnabled(true);
  }

  private void preparePlayer() {
    if (player == null) {
        // Create player instance
        createPlayer();
    }
    // Add surface to be rendered
    player.setDisplay(display);
    // Start playback session
    player.prepare();
    updateButtonVisibilities();
  }

  private void releasePlayer() {
    if (player != null) {
      // Save previous position
      playerPosition = player.getCurrentPosition();
      // Release player resources when app is paused or stopped
      player.release();
      player = null;
    }
  }

// ------------------------ VideoStreaming.Listener implementation ------------------------

    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == MediaPlayer.STATE_ENDED) {
            showControls();
        }
        String text = "playWhenReady=" +
                playWhenReady +
                ", playbackState=" +
                MediaPlayer.playbackStateToString(playbackState);
        playerStateTextView.setText(text);
        updateButtonVisibilities();
    }

    @Override
    public void onError(Exception e) {
        String errorString = getResources().getString(R.string.error_msg);
        if (errorString != null) {
            Toast.makeText(getApplicationContext(), errorString, Toast.LENGTH_LONG).show();
        }
        updateButtonVisibilities();
        showControls();
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                   float pixelWidthAspectRatio) { }

// ------------------------ User controls ------------------------

  private void updateButtonVisibilities() {
    retryButton.setVisibility((player == null || (player.getPlaybackState() == MediaPlayer.STATE_ERROR)) ? View.VISIBLE : View.GONE);
    videoButton.setVisibility(haveTracks(TrackInfo.TYPE_VIDEO) ? View.VISIBLE : View.GONE);
    audioButton.setVisibility(haveTracks(TrackInfo.TYPE_AUDIO) ? View.VISIBLE : View.GONE);
  }

  private boolean haveTracks(int type) {
    return player != null && player.getTrackCount(type) > 0;
  }

  public void showVideoPopup(View v) {
    PopupMenu popup = new PopupMenu(this, v);
    configurePopupWithTracks(popup, null, TrackInfo.TYPE_VIDEO);
    popup.show();
  }

  public void showAudioPopup(View v) {
    PopupMenu popup = new PopupMenu(this, v);
    configurePopupWithTracks(popup, null, TrackInfo.TYPE_AUDIO);
    popup.show();
  }

  private void configurePopupWithTracks(PopupMenu popup,
      final OnMenuItemClickListener customActionClickListener,
      final int trackType) {
    if (player == null) {
      return;
    }
    int trackCount = player.getTrackCount(trackType);
    if (trackCount == 0) {
      return;
    }
    popup.setOnMenuItemClickListener(new OnMenuItemClickListener() {
      @Override
      public boolean onMenuItemClick(MenuItem item) {
        return (customActionClickListener != null
            && customActionClickListener.onMenuItemClick(item))
            || onTrackItemClick(item, trackType);
      }
    });
    Menu menu = popup.getMenu();
    // TRACK_ID_OFFSET ensures we avoid clashing with Menu.NONE (which equals 0).
    menu.add(MENU_GROUP_ID_TRACKS, MediaPlayer.TRACK_DISABLED + TRACK_ID_OFFSET, Menu.NONE, R.string.off);
    for (int i = 0; i < trackCount; i++) {
      menu.add(MENU_GROUP_ID_TRACKS, i + TRACK_ID_OFFSET, Menu.NONE, player.getTrack(trackType, i).toString());
    }
    menu.setGroupCheckable(MENU_GROUP_ID_TRACKS, true, true);
    menu.findItem(player.getSelectedTrack(trackType) + TRACK_ID_OFFSET).setChecked(true);
  }

  private boolean onTrackItemClick(MenuItem item, int type) {
    if (player == null || item.getGroupId() != MENU_GROUP_ID_TRACKS) {
      return false;
    }
    player.setSelectedTrack(type, item.getItemId() - TRACK_ID_OFFSET);
    return true;
  }

  private void toggleControlsVisibility()  {
    if (mediaController.isShowing()) {
      mediaController.hide();
    } else {
      showControls();
    }
  }

  private void showControls() {
      mediaController.show(0);
  }

  private static final class KeyCompatibleMediaController extends MediaController {

     private MediaPlayerControl playerControl;

     public KeyCompatibleMediaController(Context context) {
      super(context);
    }

    @Override
    public void setMediaPlayer(MediaPlayerControl playerControl) {
      super.setMediaPlayer(playerControl);
      this.playerControl = playerControl;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
      int keyCode = event.getKeyCode();
      if (playerControl.canSeekForward() && keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
          playerControl.seekTo(playerControl.getCurrentPosition() + 15000); // milliseconds
          show();
        }
        return true;
      } else if (playerControl.canSeekBackward() && keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
          playerControl.seekTo(playerControl.getCurrentPosition() - 5000); // milliseconds
          show();
        }
        return true;
      }
      return super.dispatchKeyEvent(event);
     }
    }
}
