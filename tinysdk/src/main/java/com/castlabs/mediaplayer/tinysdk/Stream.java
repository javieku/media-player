package com.castlabs.mediaplayer.tinysdk;

import android.net.Uri;

public class Stream {

    public enum SourceType {
        MPEG_DASH,
        HLS,
        SS,
        OTHER
    }

    private SourceType type;
    private Uri uri;

    public Stream(Uri uri) {
        this.uri = uri;
        // TODO Try to infer type from extension or requesting asset
        this.type = SourceType.MPEG_DASH;
    }

    SourceType getType () {
        return type;
    }

    Uri getUri() {
        return uri;
    }

    public String toString() {
        return uri.toString();
    }

}
