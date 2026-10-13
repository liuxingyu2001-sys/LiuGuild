package com.liu.liuguild.util;

import java.util.ArrayList;
import java.util.List;

/** 分页工具：统一「总数 / 每页 / 页码」的计算，避免各 GUI 重复实现。 */
public final class Pages<T> {

    private final List<T> all;
    private final int perPage;

    public Pages(List<T> all, int perPage) {
        this.all = all == null ? new ArrayList<>() : all;
        this.perPage = Math.max(1, perPage);
    }

    public int total() {
        return all.size();
    }

    public int perPage() {
        return perPage;
    }

    public int maxPage() {
        return Math.max(0, (all.size() - 1) / perPage);
    }

    public int clamp(int page) {
        return Math.max(0, Math.min(maxPage(), page));
    }

    /** 取某页数据。 */
    public List<T> slice(int page) {
        int p = clamp(page);
        int from = p * perPage;
        int to = Math.min(all.size(), from + perPage);
        if (from >= to) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, to));
    }

    public List<T> all() {
        return all;
    }
}
