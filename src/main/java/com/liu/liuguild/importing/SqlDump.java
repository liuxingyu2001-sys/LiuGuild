package com.liu.liuguild.importing;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 MySQL dump 解析器：仅提取指定表的 {@code INSERT INTO ... VALUES (...),(...);} 数据行。
 * <p>支持单引号字符串（含 {@code \'} {@code \\} {@code \n} 等转义与字面换行）、{@code ''} 双写、{@code NULL}。</p>
 */
public final class SqlDump {

    private SqlDump() {
    }

    /** 提取某张表的全部数据行；每行为值列表（{@code null} 表示 SQL NULL）。 */
    public static List<List<String>> rows(String sql, String table) {
        List<List<String>> out = new ArrayList<>();
        String needle = "INSERT INTO `" + table + "`";
        int from = 0;
        while (true) {
            int idx = sql.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            int values = indexOfKeyword(sql, "VALUES", idx + needle.length());
            if (values < 0) {
                break;
            }
            int p = values + "VALUES".length();
            while (p < sql.length() && sql.charAt(p) != '(') {
                p++;
            }
            int i = p;
            while (i < sql.length()) {
                char ch = sql.charAt(i);
                if (ch == '(') {
                    Tuple tuple = parseTuple(sql, i);
                    out.add(tuple.values);
                    i = tuple.next;
                } else if (ch == ';') {
                    i++;
                    break;
                } else {
                    i++;
                }
            }
            from = i;
        }
        return out;
    }

    private static int indexOfKeyword(String sql, String keyword, int from) {
        int idx = sql.indexOf(keyword, from);
        return idx;
    }

    private static final class Tuple {
        final List<String> values;
        final int next;

        Tuple(List<String> values, int next) {
            this.values = values;
            this.next = next;
        }
    }

    private static Tuple parseTuple(String sql, int start) {
        List<String> values = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        int depth = 1;
        int i = start + 1;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (quoted) {
                if (ch == '\\' && i + 1 < sql.length()) {
                    char n = sql.charAt(i + 1);
                    cur.append(unescape(n));
                    i += 2;
                    continue;
                }
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    closed = true;
                    i++;
                    continue;
                }
                cur.append(ch);
                i++;
                continue;
            }
            if (ch == '\'') {
                quoted = true;
                i++;
                continue;
            }
            if (ch == ',' || ch == ')') {
                values.add(finish(cur, closed));
                cur.setLength(0);
                closed = false;
                if (ch == ')') {
                    return new Tuple(values, i + 1);
                }
                i++;
                continue;
            }
            if (ch == '(') {
                // 理论上不应出现（值里没有未加引号的括号）
                depth++;
                cur.append(ch);
                i++;
                continue;
            }
            cur.append(ch);
            i++;
        }
        values.add(finish(cur, closed));
        return new Tuple(values, sql.length());
    }

    private static String finish(StringBuilder cur, boolean quoted) {
        String v = cur.toString();
        if (!quoted) {
            v = v.trim();
            if (v.equalsIgnoreCase("NULL")) {
                return null;
            }
        }
        return v;
    }

    private static char unescape(char n) {
        return switch (n) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '0' -> '\0';
            case 'b' -> '\b';
            default -> n;
        };
    }
}
