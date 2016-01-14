package com.castlabs.mediaplayer.tinysdk;

import android.content.Context;

import com.castlabs.mediaplayer.tinysdk.MediaPlayer.RendererBuilder;
import com.google.android.exoplayer.drm.MediaDrmCallback;

/**
 * A {@link RendererBuilder} for SS.
 */
public class SmoothStreamingRendererBuilder implements RendererBuilder {

    SmoothStreamingRendererBuilder(Context context, String userAgent, String url,
                              MediaDrmCallback drmCallback) {
    }

    @Override
    public void buildRenderers(MediaPlayer player) {
    }

    @Override
    public void cancel() {

    }
}

