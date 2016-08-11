/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.source;

import android.net.Uri;
import android.os.Handler;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.Loader.Loadable;
import com.google.android.exoplayer2.util.Assertions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Loads data at a given {@link Uri} as a single sample belonging to a single {@link MediaPeriod}.
 */
public final class SingleSampleMediaSource implements MediaPeriod, MediaSource,
    Loader.Callback<SingleSampleMediaSource.SourceLoadable> {

  /**
   * Listener of {@link SingleSampleMediaSource} events.
   */
  public interface EventListener {

    /**
     * Called when an error occurs loading media data.
     *
     * @param sourceId The id of the reporting {@link SingleSampleMediaSource}.
     * @param e The cause of the failure.
     */
    void onLoadError(int sourceId, IOException e);

  }

  /**
   * The default minimum number of times to retry loading data prior to failing.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

  /**
   * The initial size of the allocation used to hold the sample data.
   */
  private static final int INITIAL_SAMPLE_SIZE = 1;

  private final Uri uri;
  private final DataSource.Factory dataSourceFactory;
  private final long durationUs;
  private final int minLoadableRetryCount;
  private final TrackGroupArray tracks;
  private final Handler eventHandler;
  private final EventListener eventListener;
  private final int eventSourceId;
  private final ArrayList<SampleStreamImpl> sampleStreams;
  /* package */ final Format format;

  /* package */ Loader loader;
  /* package */ boolean loadingFinished;
  /* package */ byte[] sampleData;
  /* package */ int sampleSize;

  public SingleSampleMediaSource(Uri uri, DataSource.Factory dataSourceFactory, Format format,
      long durationUs) {
    this(uri, dataSourceFactory, format, durationUs, DEFAULT_MIN_LOADABLE_RETRY_COUNT);
  }

  public SingleSampleMediaSource(Uri uri, DataSource.Factory dataSourceFactory, Format format,
      long durationUs, int minLoadableRetryCount) {
    this(uri, dataSourceFactory, format, durationUs, minLoadableRetryCount, null, null, 0);
  }

  public SingleSampleMediaSource(Uri uri, DataSource.Factory dataSourceFactory, Format format,
      long durationUs, int minLoadableRetryCount, Handler eventHandler, EventListener eventListener,
      int eventSourceId) {
    this.uri = uri;
    this.dataSourceFactory = dataSourceFactory;
    this.format = format;
    this.durationUs = durationUs;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.eventSourceId = eventSourceId;
    tracks = new TrackGroupArray(new TrackGroup(format));
    sampleData = new byte[INITIAL_SAMPLE_SIZE];
    sampleStreams = new ArrayList<>();
  }

  // MediaSource implementation.

  @Override
  public void prepareSource(MediaSource.Listener listener) {
    Timeline timeline = SinglePeriodTimeline.createSeekableFinalTimeline(this, durationUs);
    listener.onSourceInfoRefreshed(timeline, null);
  }

  @Override
  public int getNewPlayingPeriodIndex(int oldPlayingPeriodIndex, Timeline oldTimeline) {
    return oldPlayingPeriodIndex;
  }

  @Override
  public Position getDefaultStartPosition(int index) {
    return Position.DEFAULT;
  }

  @Override
  public MediaPeriod createPeriod(int index) {
    Assertions.checkArgument(index == 0);
    return this;
  }

  @Override
  public void releaseSource() {
    // Do nothing.
  }

  // MediaPeriod implementation.

  @Override
  public void preparePeriod(Callback callback, Allocator allocator, long positionUs) {
    loader = new Loader("Loader:SingleSampleMediaSource");
    callback.onPeriodPrepared(this);
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    // Do nothing.
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return tracks;
  }

  @Override
  public long selectTracks(TrackSelection[] selections, boolean[] mayRetainStreamFlags,
      SampleStream[] streams, boolean[] streamResetFlags, long positionUs) {
    for (int i = 0; i < selections.length; i++) {
      if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
        sampleStreams.remove(streams[i]);
        streams[i] = null;
      }
      if (streams[i] == null && selections[i] != null) {
        SampleStreamImpl stream = new SampleStreamImpl();
        sampleStreams.add(stream);
        streams[i] = stream;
        streamResetFlags[i] = true;
      }
    }
    return positionUs;
  }

  @Override
  public boolean continueLoading(long positionUs) {
    if (loadingFinished || loader.isLoading()) {
      return false;
    }
    loader.startLoading(new SourceLoadable(uri, dataSourceFactory.createDataSource()), this,
        minLoadableRetryCount);
    return true;
  }

  @Override
  public long readDiscontinuity() {
    return C.UNSET_TIME_US;
  }

  @Override
  public long getNextLoadPositionUs() {
    return loadingFinished || loader.isLoading() ? C.END_OF_SOURCE_US : 0;
  }

  @Override
  public long getBufferedPositionUs() {
    return loadingFinished ? C.END_OF_SOURCE_US : 0;
  }

  @Override
  public long seekToUs(long positionUs) {
    for (int i = 0; i < sampleStreams.size(); i++) {
      sampleStreams.get(i).seekToUs(positionUs);
    }
    return positionUs;
  }

  @Override
  public void releasePeriod() {
    if (loader != null) {
      loader.release();
      loader = null;
    }
    loadingFinished = false;
    sampleStreams.clear();
    sampleData = null;
    sampleSize = 0;
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(SourceLoadable loadable, long elapsedRealtimeMs,
      long loadDurationMs) {
    sampleSize = loadable.sampleSize;
    sampleData = loadable.sampleData;
    loadingFinished = true;
  }

  @Override
  public void onLoadCanceled(SourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs,
      boolean released) {
    // Do nothing.
  }

  @Override
  public int onLoadError(SourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs,
      IOException error) {
    notifyLoadError(error);
    return Loader.RETRY;
  }

  // Internal methods.

  private void notifyLoadError(final IOException e) {
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable() {
        @Override
        public void run() {
          eventListener.onLoadError(eventSourceId, e);
        }
      });
    }
  }

  private final class SampleStreamImpl implements SampleStream {

    private static final int STREAM_STATE_SEND_FORMAT = 0;
    private static final int STREAM_STATE_SEND_SAMPLE = 1;
    private static final int STREAM_STATE_END_OF_STREAM = 2;

    private int streamState;

    public void seekToUs(long positionUs) {
      if (streamState == STREAM_STATE_END_OF_STREAM) {
        streamState = STREAM_STATE_SEND_SAMPLE;
      }
    }

    @Override
    public boolean isReady() {
      return loadingFinished;
    }

    @Override
    public void maybeThrowError() throws IOException {
      loader.maybeThrowError();
    }

    @Override
    public int readData(FormatHolder formatHolder, DecoderInputBuffer buffer) {
      if (streamState == STREAM_STATE_END_OF_STREAM) {
        buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        return C.RESULT_BUFFER_READ;
      } else if (streamState == STREAM_STATE_SEND_FORMAT) {
        formatHolder.format = format;
        streamState = STREAM_STATE_SEND_SAMPLE;
        return C.RESULT_FORMAT_READ;
      }

      Assertions.checkState(streamState == STREAM_STATE_SEND_SAMPLE);
      if (!loadingFinished) {
        return C.RESULT_NOTHING_READ;
      } else {
        buffer.timeUs = 0;
        buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME);
        buffer.ensureSpaceForWrite(sampleSize);
        buffer.data.put(sampleData, 0, sampleSize);
        streamState = STREAM_STATE_END_OF_STREAM;
        return C.RESULT_BUFFER_READ;
      }
    }

    @Override
    public void skipToKeyframeBefore(long timeUs) {
      // do nothing
    }

  }

  /* package */ static final class SourceLoadable implements Loadable {

    private final Uri uri;
    private final DataSource dataSource;

    private int sampleSize;
    private byte[] sampleData;

    public SourceLoadable(Uri uri, DataSource dataSource) {
      this.uri = uri;
      this.dataSource = dataSource;
    }

    @Override
    public void cancelLoad() {
      // Never happens.
    }

    @Override
    public boolean isLoadCanceled() {
      return false;
    }

    @Override
    public void load() throws IOException, InterruptedException {
      // We always load from the beginning, so reset the sampleSize to 0.
      sampleSize = 0;
      try {
        // Create and open the input.
        dataSource.open(new DataSpec(uri));
        // Load the sample data.
        int result = 0;
        while (result != C.RESULT_END_OF_INPUT) {
          sampleSize += result;
          if (sampleSize == sampleData.length) {
            sampleData = Arrays.copyOf(sampleData, sampleData.length * 2);
          }
          result = dataSource.read(sampleData, sampleSize, sampleData.length - sampleSize);
        }
      } finally {
        dataSource.close();
      }
    }

  }

}
