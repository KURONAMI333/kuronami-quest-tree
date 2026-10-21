package com.kuronami.questtree.server;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * The only class that mentions FTB Library's currency hook, and it does so through reflection: the common
 * compile-only artifact carries intermediary parameter names, which would not link against the mapped game. Called
 * only when FTB Library is loaded, so a pack without it never loads this class. Currency mods (FTB Money and others)
 * register the paying half with FTB Library; whichever one is installed is the coin a {@code currency} reward pays.
 */
final class FtbLibraryCurrencyBridge {

    private static final String HELPER = "dev.ftb.mods.ftblibrary.integration.currency.CurrencyHelper";

    private FtbLibraryCurrencyBridge() {
    }

    /** The registered provider, or null when no economy mod is installed or the lookup failed. */
    private static Object provider() {
        try {
            Class<?> helper = Class.forName(HELPER);
            Object instance = helper.getMethod("getInstance").invoke(null);
            Object provider = helper.getMethod("getProvider").invoke(instance);
            if (provider == null) {
                return null;
            }
            Method valid = provider.getClass().getMethod("isValidProvider");
            return Boolean.TRUE.equals(valid.invoke(provider)) ? provider : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /** Whether an economy mod has registered a working provider with FTB Library. */
    static boolean available() {
        return provider() != null;
    }

    static String name() {
        Object provider = provider();
        if (provider == null) {
            return "";
        }
        try {
            return String.valueOf(provider.getClass().getMethod("getName").invoke(provider));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "";
        }
    }

    /** Pays the player. A provider that throws grants nothing and reports failure to the caller. */
    static boolean give(ServerPlayer player, int amount) {
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        try {
            provider.getClass().getMethod("giveCurrency", net.minecraft.world.entity.player.Player.class)
                    .invoke(provider, player, amount);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }
}
