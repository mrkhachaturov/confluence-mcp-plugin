package com.atlassian.mcp.plugin.rest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Ends Reactor's threads when the plugin stops.
 *
 * <p>Reactor is embedded in this bundle ({@code Private-Package}), so every scheduler it starts
 * belongs to the plugin, and a thread that outlives the plugin keeps the plugin's class loader
 * alive across every disable, enable and upgrade. The SDK transport starts its idle-session sweeper
 * on a scheduler of its own, {@code Schedulers.newSingle(...)}, and on shutdown it cancels the
 * sweep but never disposes that scheduler, leaving a non-daemon thread behind. The SDK keeps no
 * handle to it, so the factory installed here records each one as it is created.
 *
 * <p>Reactor's {@code Schedulers} state is global, but only to this bundle's copy of Reactor.
 */
final class PluginSchedulers {

  private static final Set<Scheduler> SINGLES = ConcurrentHashMap.newKeySet();

  private static final Schedulers.Factory RECORDING_FACTORY =
      new Schedulers.Factory() {
        @Override
        public Scheduler newSingle(ThreadFactory threadFactory) {
          Scheduler scheduler = Schedulers.Factory.super.newSingle(threadFactory);
          SINGLES.add(scheduler);
          return scheduler;
        }
      };

  private PluginSchedulers() {}

  /** Call before building the transport, so the schedulers it creates are recorded. */
  static void install() {
    Schedulers.setFactory(RECORDING_FACTORY);
  }

  /** Call after the transport has shut down. */
  static void shutdown() {
    for (Scheduler scheduler : SINGLES) {
      scheduler.dispose();
    }
    SINGLES.clear();
    Schedulers.resetFactory();
    Schedulers.shutdownNow();
  }
}
