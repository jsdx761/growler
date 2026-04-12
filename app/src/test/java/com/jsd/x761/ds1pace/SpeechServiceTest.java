package com.jsd.x761.ds1pace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for SpeechService audio focus management and callback handling.
 * Uses Robolectric to provide Android service stubs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SpeechServiceTest {

  private SpeechService mService;

  @Before
  public void setUp() {
    mService = Robolectric.setupService(SpeechService.class);
  }

  /** Flush pending handler messages including delayed ones. */
  private void flushLooper() {
    ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  // --- Audio Focus Counting ---

  @Test
  public void requestAudioFocus_callsOnDone() {
    AtomicBoolean done = new AtomicBoolean(false);
    mService.requestAudioFocus(() -> done.set(true));
    flushLooper();
    assertTrue("onDone should be called after requestAudioFocus", done.get());
  }

  @Test
  public void abandonAudioFocus_callsOnDone() {
    // First request, then abandon
    mService.requestAudioFocus(() -> {});
    flushLooper();
    AtomicBoolean done = new AtomicBoolean(false);
    mService.abandonAudioFocus(() -> done.set(true));
    assertTrue("onDone should be called after abandonAudioFocus", done.get());
  }

  @Test
  public void requestAudioFocus_multiple_requiresMultipleAbandons() {
    AtomicInteger abandonCount = new AtomicInteger(0);

    // Request focus 3 times (simulating concurrent announcements)
    mService.requestAudioFocus(() -> {});
    mService.requestAudioFocus(() -> {});
    mService.requestAudioFocus(() -> {});

    // Abandon 3 times - all should complete without error
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());

    assertEquals(3, abandonCount.get());
  }

  @Test
  public void abandonAudioFocus_withoutRequest_doesNotUnderflow() {
    // Abandon without prior request should still call onDone safely
    AtomicBoolean done = new AtomicBoolean(false);
    mService.abandonAudioFocus(() -> done.set(true));
    assertTrue("onDone should be called even without prior request", done.get());
  }

  @Test
  public void abandonAudioFocus_extraAbandons_doNotUnderflow() {
    // Request once, abandon three times
    mService.requestAudioFocus(() -> {});

    AtomicInteger abandonCount = new AtomicInteger(0);
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());
    mService.abandonAudioFocus(() -> abandonCount.incrementAndGet());

    // All should complete without exception
    assertEquals(3, abandonCount.get());
  }

  // --- Callback Management ---

  @Test
  public void addAndRemoveCallback() {
    AtomicBoolean called = new AtomicBoolean(false);
    mService.addOnUtteranceProgressCallback("test-uuid", () -> called.set(true));

    Runnable callback = mService.mTextToSpeechCallback.get("test-uuid");
    assertTrue("Callback should be registered", callback != null);

    mService.removeOnUtteranceProgressCallback("test-uuid");
    callback = mService.mTextToSpeechCallback.get("test-uuid");
    assertTrue("Callback should be removed", callback == null);
  }

  @Test
  public void addCallback_overwritesSameKey() {
    AtomicInteger counter = new AtomicInteger(0);
    mService.addOnUtteranceProgressCallback("uuid1", () -> counter.set(1));
    mService.addOnUtteranceProgressCallback("uuid1", () -> counter.set(2));

    Runnable callback = mService.mTextToSpeechCallback.get("uuid1");
    callback.run();
    assertEquals("Second callback should overwrite first", 2, counter.get());
  }

  @Test
  public void removeCallback_nonExistentKey_doesNotThrow() {
    // Should not throw
    mService.removeOnUtteranceProgressCallback("nonexistent");
  }

  @Test
  public void multipleCallbacks_independentKeys() {
    AtomicBoolean called1 = new AtomicBoolean(false);
    AtomicBoolean called2 = new AtomicBoolean(false);

    mService.addOnUtteranceProgressCallback("uuid1", () -> called1.set(true));
    mService.addOnUtteranceProgressCallback("uuid2", () -> called2.set(true));

    Runnable callback1 = mService.mTextToSpeechCallback.get("uuid1");
    callback1.run();

    assertTrue("uuid1 callback should have been called", called1.get());
    assertFalse("uuid2 callback should not have been called", called2.get());
  }

  @Test
  public void callbackMap_nullGet_doesNotCrash() {
    // Simulates the earcon scenario where no callback is registered
    Runnable callback = mService.mTextToSpeechCallback.get("no-such-uuid");
    assertTrue("Should return null for unregistered UUID", callback == null);
  }

  // --- Speech Queue ---

  @Test
  public void enqueueSpeech_startsFirstImmediately() {
    AtomicBoolean ran = new AtomicBoolean(false);
    mService.enqueueSpeech(() -> ran.set(true));
    flushLooper();
    assertTrue("First enqueued speech should start immediately", ran.get());
    assertTrue("mSpeaking should be true while speech is active", mService.mSpeaking);
  }

  @Test
  public void enqueueSpeech_queuesSecondWhileFirstIsActive() {
    AtomicInteger startCount = new AtomicInteger(0);

    // First speech starts after audio focus settle
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    flushLooper();
    assertEquals("First speech should have started", 1, startCount.get());

    // Second speech is queued, not started
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    assertEquals("Second speech should NOT have started yet", 1, startCount.get());
    assertEquals("Queue should have one pending item", 1, mService.mSpeechQueue.size());
  }

  @Test
  public void processNextSpeech_startsSecondAfterFirstCompletes() {
    AtomicInteger startCount = new AtomicInteger(0);
    List<Integer> startOrder = new ArrayList<>();

    mService.enqueueSpeech(() -> { startCount.incrementAndGet(); startOrder.add(1); });
    mService.enqueueSpeech(() -> { startCount.incrementAndGet(); startOrder.add(2); });
    mService.enqueueSpeech(() -> { startCount.incrementAndGet(); startOrder.add(3); });
    flushLooper();

    assertEquals("Only first should have started", 1, startCount.get());

    // Simulate first speech completing
    mService.processNextSpeech();
    assertEquals("Second should now have started", 2, startCount.get());

    // Simulate second speech completing
    mService.processNextSpeech();
    assertEquals("Third should now have started", 3, startCount.get());

    // Simulate third completing — queue empty
    mService.processNextSpeech();
    assertFalse("mSpeaking should be false when queue is empty", mService.mSpeaking);

    // Verify order
    assertEquals(List.of(1, 2, 3), startOrder);
  }

  @Test
  public void enqueueSpeech_noConcurrentExecution() {
    AtomicInteger concurrent = new AtomicInteger(0);
    AtomicInteger maxConcurrent = new AtomicInteger(0);

    Runnable trackConcurrency = () -> {
      int c = concurrent.incrementAndGet();
      maxConcurrent.set(Math.max(maxConcurrent.get(), c));
    };

    // Enqueue 3 speech actions
    mService.enqueueSpeech(trackConcurrency);
    mService.enqueueSpeech(trackConcurrency);
    mService.enqueueSpeech(trackConcurrency);
    flushLooper();

    // Only one should be running
    assertEquals("Max concurrent should be 1 after enqueue", 1, maxConcurrent.get());

    // Simulate completions
    concurrent.decrementAndGet();
    mService.processNextSpeech();
    assertEquals("Max concurrent should still be 1", 1, maxConcurrent.get());

    concurrent.decrementAndGet();
    mService.processNextSpeech();
    assertEquals("Max concurrent should still be 1", 1, maxConcurrent.get());
  }

  @Test
  public void stopSpeech_clearsQueue() {
    AtomicInteger startCount = new AtomicInteger(0);

    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    flushLooper();

    assertEquals("Only first should have started", 1, startCount.get());

    mService.stopSpeech();

    assertFalse("mSpeaking should be false after stop", mService.mSpeaking);
    assertTrue("Queue should be empty after stop", mService.mSpeechQueue.isEmpty());

    // New speech after stop should work normally
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    flushLooper();
    assertEquals("New speech after stop should start", 2, startCount.get());
  }

  @Test
  public void enqueueSpeech_afterQueueDrains_startsNewSpeechImmediately() {
    AtomicInteger startCount = new AtomicInteger(0);

    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    flushLooper();
    assertEquals(1, startCount.get());

    // Drain queue
    mService.processNextSpeech();
    assertFalse(mService.mSpeaking);

    // New speech should start after audio focus settle
    mService.enqueueSpeech(() -> startCount.incrementAndGet());
    flushLooper();
    assertEquals(2, startCount.get());
    assertTrue(mService.mSpeaking);
  }

  // --- Voice Mode ---

  @Test
  public void voiceMode_defaultIsSystem() {
    assertEquals(Configuration.VOICE_MODE_SYSTEM, mService.getVoiceMode());
  }

  @Test
  public void isPreRecordedReady_defaultFalse() {
    assertFalse("Pre-recorded engine should not be ready before init",
      mService.isPreRecordedReady());
  }

  @Test
  public void getUsePreRecorded_defaultFalse() {
    assertFalse("Pre-recorded should be disabled by default",
      mService.getUsePreRecorded());
  }

  // --- Service lifecycle ---

  @Test
  public void onDestroy_cleansUp() {
    mService.requestAudioFocus(() -> {});
    mService.requestAudioFocus(() -> {});
    mService.addOnUtteranceProgressCallback("test", () -> {});

    // Should not throw
    mService.onDestroy();
  }

  @Test
  public void onDestroy_clearsQueue() {
    mService.enqueueSpeech(() -> {});
    mService.enqueueSpeech(() -> {});
    flushLooper();

    assertTrue(mService.mSpeaking);
    assertEquals(1, mService.mSpeechQueue.size());

    mService.onDestroy();

    assertFalse("mSpeaking should be false after destroy", mService.mSpeaking);
    assertTrue("Queue should be empty after destroy", mService.mSpeechQueue.isEmpty());
  }
}
