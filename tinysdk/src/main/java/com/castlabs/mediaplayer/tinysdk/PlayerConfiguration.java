package com.castlabs.mediaplayer.tinysdk;

import android.content.Context;

public class PlayerConfiguration {

    private String userAgent;
    private Context appContext;

    public PlayerConfiguration (Context appContext, String userAgent) {
        this.userAgent = userAgent;
        this.appContext = appContext;
    }

    public Context getAppContext() { return appContext; }

    public String getUserAgent() { return userAgent; }

}
