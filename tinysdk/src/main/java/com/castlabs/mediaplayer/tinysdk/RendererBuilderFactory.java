package com.castlabs.mediaplayer.tinysdk;

import android.content.Context;

import com.castlabs.mediaplayer.tinysdk.MediaPlayer.RendererBuilder;

public class RendererBuilderFactory {

   static RendererBuilder createRendererBuilder (Stream playable, PlayerConfiguration playerConfiguration){
       Context appContext = playerConfiguration.getAppContext();
       String userAgent = playerConfiguration.getUserAgent();
        switch (playable.getType()) {
            case HLS:
                return new SmoothStreamingRendererBuilder(appContext, userAgent, playable.toString(), null);
            case MPEG_DASH:
                return new DashRendererBuilder(appContext, userAgent, playable.toString(), null);
            case SS:
                return new HLSRendererBuilder(appContext, userAgent, playable.toString(), null);
            default:
                throw new IllegalStateException("Unsupported type: ");
        }
    }

}
