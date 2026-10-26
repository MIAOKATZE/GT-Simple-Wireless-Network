package com.miaokatze.gtswn.common.monitoring.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Main-thread authorized projection. Deliberately contains no native world, player, item or tile reference. */
public final class WebDashboardSnapshot {

    public static final int MAX_CARDS = 128;
    public static final int MAX_HISTORY = 120;
    public final UUID id;
    public final String title;
    public final long revision;
    public final String status;
    public final long sampleTick;
    public final List<Card> cards;
    public final int omittedCards;

    public WebDashboardSnapshot(UUID id, String title, long revision, String status, long sampleTick,
        List<Card> cards) {
        this(id, title, revision, status, sampleTick, cards, 0);
    }

    public WebDashboardSnapshot(UUID id, String title, long revision, String status, long sampleTick, List<Card> cards,
        int omittedCards) {
        if (id == null || cards == null) throw new IllegalArgumentException("Missing dashboard fields");
        this.id = id;
        this.title = bounded(title);
        this.revision = revision;
        this.status = bounded(status);
        this.sampleTick = sampleTick;
        this.omittedCards = Math.max(0, omittedCards) + Math.max(0, cards.size() - MAX_CARDS);
        this.cards = Collections.unmodifiableList(new ArrayList<>(cards.subList(0, Math.min(MAX_CARDS, cards.size()))));
    }

    static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(256, value.length()));
    }

    public static final class Card {

        public final String label;
        public final String value;
        public final String unit;
        private final double[] history;

        public Card(String label, String value, String unit, double[] history) {
            this.label = bounded(label);
            // A shortened numeric prefix must never look like a complete, exact balance.
            this.value = value != null && value.length() > 256 ? value.substring(0, 255) + "…" : bounded(value);
            this.unit = bounded(unit);
            int length = history == null ? 0 : Math.min(MAX_HISTORY, history.length);
            this.history = new double[length];
            if (length > 0) System.arraycopy(history, history.length - length, this.history, 0, length);
        }

        public double[] history() {
            return history.clone();
        }
    }
}
