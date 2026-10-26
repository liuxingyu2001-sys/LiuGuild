package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Vault 经济钩子（软依赖）。
 *
 * <p>这里刻意用反射而不是直接引用 {@code net.milkbowl.vault.economy.Economy}：
 * Vault 只是 {@code softdepend}，未安装时插件类加载器里根本没有 Vault API。
 * 如果本类带直接类型引用，主类即使只想构造一个“无经济”的降级实例也会触发
 * {@link NoClassDefFoundError}。反射让本类在任何环境下都能安全加载，所有方法在
 * 无 Vault 时按“无经济”语义降级：检查放行、扣款成功、存款失败、余额 0。</p>
 */
public final class EconomyHook {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private Object economy;
    private Method hasMethod;
    private Method withdrawMethod;
    private Method depositMethod;
    private Method formatMethod;
    private Method balanceMethod;
    private Method transactionSuccessMethod;

    public EconomyHook() {
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null || !vault.isEnabled()) {
                return;
            }
            ClassLoader loader = vault.getClass().getClassLoader();
            Class<?> api = Class.forName(ECONOMY_CLASS, false, loader);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object registration = Bukkit.getServicesManager().getRegistration((Class) api);
            if (registration == null) {
                return;
            }
            Object provider = registration.getClass().getMethod("getProvider").invoke(registration);
            if (provider == null || !api.isInstance(provider)) {
                return;
            }
            Method has = api.getMethod("has", OfflinePlayer.class, double.class);
            Method withdraw = api.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Method deposit = api.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Method format = api.getMethod("format", double.class);
            Method balance = api.getMethod("getBalance", OfflinePlayer.class);
            Class<?> response = Class.forName(
                    "net.milkbowl.vault.economy.EconomyResponse", false, loader);
            Method success = response.getMethod("transactionSuccess");
            this.economy = provider;
            this.hasMethod = has;
            this.withdrawMethod = withdraw;
            this.depositMethod = deposit;
            this.formatMethod = format;
            this.balanceMethod = balance;
            this.transactionSuccessMethod = success;
        } catch (Throwable ignored) {
            // Vault 未安装、API 版本不兼容或经济服务尚未注册：保持无经济降级。
            this.economy = null;
        }
    }

    public boolean available() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null || player == null) {
            return true; // 无经济插件时不阻拦
        }
        try {
            return Boolean.TRUE.equals(hasMethod.invoke(economy, player, amount));
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || player == null) {
            return true;
        }
        try {
            return transactionSuccess(withdrawMethod.invoke(economy, player, amount));
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null || player == null) {
            return false;
        }
        try {
            return transactionSuccess(depositMethod.invoke(economy, player, amount));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean transactionSuccess(Object response) {
        if (response == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(transactionSuccessMethod.invoke(response));
        } catch (Throwable t) {
            return false;
        }
    }

    public String format(double amount) {
        if (economy == null) {
            return String.format("%.0f", amount);
        }
        try {
            return String.valueOf(formatMethod.invoke(economy, amount));
        } catch (Throwable t) {
            return String.format("%.0f", amount);
        }
    }

    /** 玩家当前余额；无经济插件或查询失败返回 0。 */
    public double balance(OfflinePlayer player) {
        if (economy == null || player == null) {
            return 0;
        }
        try {
            Object value = balanceMethod.invoke(economy, player);
            return value instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
