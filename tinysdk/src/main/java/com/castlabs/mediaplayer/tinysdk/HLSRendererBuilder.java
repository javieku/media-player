package com.castlabs.mediaplayer.tinysdk;

import android.content.Context;

import com.google.android.exoplayer.drm.MediaDrmCallback;

/**
 * A {@link MediaPlayer.RendererBuilder} for HLS.
 */
public class HLSRendererBuilder implements MediaPlayer.RendererBuilder {

     HLSRendererBuilder(Context context, String userAgent, String url,
                               MediaDrmCallback drmCallback) {
    }

    @Override
    public void buildRenderers(MediaPlayer player) {
    }

    @Override
    public void cancel() {

    }

}

