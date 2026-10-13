package com.liu.liuguild.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Vault 经济钩子（软依赖，缺失时全部方法为无操作并返回 false）。 */
public final class EconomyHook {

    private Economy economy;

    public EconomyHook() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean available() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) {
            return true; // 无经济插件时不阻拦
        }
        try {
            return economy.has(player, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) {
            return true;
        }
        try {
            return economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) {
            return false;
        }
        try {
            return economy.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    public String format(double amount) {
        if (economy == null) {
            return String.format("%.0f", amount);
        }
        try {
            return economy.format(amount);
        } catch (Throwable t) {
            return String.format("%.0f", amount);
        }
    }
}
