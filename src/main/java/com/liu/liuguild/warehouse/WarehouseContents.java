package com.liu.liuguild.warehouse;

import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** 与界面槽位无关的逻辑堆叠，真实数量永不写入展示物品。 */
public final class WarehouseContents {
    public static final int MAX_AMOUNT = Integer.MAX_VALUE;
    public static final class Entry {
        private final ItemStack sample;
        private int amount;
        public Entry(ItemStack sample, int amount) {
            if (sample == null || sample.getType().isAir() || amount <= 0) throw new IllegalArgumentException("Invalid stack");
            this.sample = sample.clone();
            this.sample.setAmount(1);
            this.amount = amount;
        }
        public ItemStack sample() { return sample.clone(); }
        public int amount() { return amount; }
    }
    private final List<Entry> entries = new ArrayList<>();
    public List<Entry> entries() { return List.copyOf(entries); }
    public void load(ItemStack sample, int amount) { entries.add(new Entry(sample, amount)); }
    public WarehouseContents copy() {
        WarehouseContents copy = new WarehouseContents();
        for (Entry e : entries) copy.load(e.sample, e.amount);
        return copy;
    }
    /** 返回实际存入数；容量以不同逻辑堆叠计数，已有堆叠在缩容后仍可合并。 */
    public int deposit(ItemStack sample, int amount, int capacity, boolean existingOnly) {
        if (sample == null || sample.getType().isAir() || amount <= 0) return 0;
        int left = amount;
        boolean found = false;
        for (Entry e : entries) {
            if (!e.sample.isSimilar(sample)) continue;
            found = true;
            int move = Math.min(left, MAX_AMOUNT - e.amount);
            e.amount += move;
            left -= move;
            if (left == 0) break;
        }
        if (left > 0 && entries.size() < capacity && (!existingOnly || found)) {
            entries.add(new Entry(sample, left));
            left = 0;
        }
        return amount - left;
    }
    public int withdraw(Entry entry, int requested) {
        if (!entries.contains(entry) || requested <= 0) return 0;
        int take = Math.min(requested, entry.amount);
        entry.amount -= take;
        if (entry.amount == 0) entries.remove(entry);
        return take;
    }
}
