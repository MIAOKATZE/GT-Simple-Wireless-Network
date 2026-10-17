package com.miaokatze.gtswn.common.baubles;

/** Reference-preserving inventory transactions, independent of Minecraft and optional mods. */
public final class TerminalInventoryRules {

    private TerminalInventoryRules() {}

    public interface Inventory<T> {

        int size();

        T get(int slot);

        void set(int slot, T stack);
    }

    public static int[] select(int[] categories) {
        int[] selected = { -1, -1, -1, -1, -1, -1, -1, -1 };
        int quantum = 3;
        for (int i = 0; i < categories.length; i++) {
            int category = categories[i];
            if (category >= 0 && category < 3 && selected[category] < 0) selected[category] = i;
            else if (category == 3 && quantum < 8) selected[quantum++] = i;
        }
        return selected;
    }

    public static <T> Borrow<T> take(Inventory<T> main, int held, Inventory<T> baubles, int origin) {
        if (held < 0 || held >= Math.min(9, main.size()) || origin < 0 || origin >= baubles.size()) return null;
        T terminal = baubles.get(origin);
        if (terminal == null) return null;
        T displaced = main.get(held);
        int free = -1;
        if (displaced != null) {
            for (int distance = 1; distance < 9; distance++) {
                int candidate = (held + distance) % 9;
                if (candidate < main.size() && main.get(candidate) == null) {
                    free = candidate;
                    break;
                }
            }
            if (free < 0) for (int i = 9; i < main.size(); i++) {
                if (main.get(i) == null) {
                    free = i;
                    break;
                }
            }
            if (free < 0) return null;
        }
        baubles.set(origin, null);
        if (displaced != null) main.set(free, displaced);
        main.set(held, terminal);
        return new Borrow<>(held, origin, terminal);
    }

    public static final class Borrow<T> {

        public final int held, origin;
        public final T stack;

        private Borrow(int held, int origin, T stack) {
            this.held = held;
            this.origin = origin;
            this.stack = stack;
        }

        public boolean present(Inventory<T> main) {
            return main.get(held) == stack;
        }

        public boolean valid(Inventory<T> main, int current) {
            return current == held && present(main);
        }

        public boolean restore(Inventory<T> main, int current, Inventory<T> baubles) {
            if (!valid(main, current) || origin >= baubles.size() || baubles.get(origin) != null) return false;
            main.set(held, null);
            baubles.set(origin, stack);
            return true;
        }
    }
}
