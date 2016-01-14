/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.castlabs.appdemo;

import java.util.Locale;

/**
 * Holds statically defined sample definitions.
 */
class StreamSamples {

  public static class StreamSample {

    public final String name;
    public final String contentId;
    public final String provider;
    public final String uri;

    public StreamSample(String name, String uri) {
      this(name, name.toLowerCase(Locale.US).replaceAll("\\s", ""), "FOX", uri);
    }

    public StreamSample(String name, String contentId, String provider, String uri) {
      this.name = name;
      this.contentId = contentId;
      this.provider = provider;
      this.uri = uri;
    }
  }

  public static final StreamSample[] DASH_MP4 = new StreamSample[] {
    new StreamSample("Smurfs",
        " http://demo.unified-streaming.com/video/smurfs/smurfs.ism/smurfs.mpd"),
    new StreamSample("Formula 1",
        "http://dash.edgesuite.net/envivio/dashpr/clear/Manifest.mpd"),
  };

  private StreamSamples() {}

}
